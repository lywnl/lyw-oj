# 设计

## 边界与现状

以用户原文计划为约束，只改池化路径。现有 Worker 对容器执行独占任务，但健康检查只看 Running，编译无超时，运行超时仅删除源码，销毁前释放名额且提前写销毁标记。已有幂等和系统错误语义继续保留；不修改未归档的其他变更及其证据。

## 执行限制与接口

集中提供不可变 SandboxLimits：memory=100000000、swap=100000000、cpu=1、pids=128、compileTimeoutMs=10000、minimumRunMs=5000、runGraceMs=1000、outputMaxBytes=1048576、cleanupTimeoutMs=10000。沿用用户计划的配置键，正数及 long 换算检查。内部池化 exeJavaCode 增加 Long questionTimeLimitMs；调用方在入队前校验并计算 max(minimumRunMs, addExact(questionTimeLimitMs, grace))，外部消息不变。

编译及运行通过一个受限执行辅助方法执行。共享控制客户端负责 create/inspect/remove 等短请求，采用同版本 HttpClient5，连接 3 秒、响应等待 cleanupTimeoutMs；Netty 工厂为每个 Worker 创建独占的 exec/stats 流客户端，正常任务复用，退休时关闭，不能给流强加短响应超时。工厂与控制客户端明确区分，客户端均有生命周期管理。Docker API 版本沿用可配置能力，真实测试用服务端支持的 1.41。

池化专用回调使用任务共享字节预算和有界字节缓冲。超限仅设置标记并唤醒 Worker，回调线程不阻塞执行 Docker 回收；Worker 关闭流并淘汰。输出原始字节合并后按 UTF-8 解码，stdout/stderr 均计入预算。exec 的正常完成、错误、超限分开表示；只有 inspect 确认 Running=false 且取得退出码才认定命令退出。stats 流显式关闭，继续使用现有 getMaxUsage 统计口径。

已核对 docker-java 3.3.4 的 NettyInvocationBuilder：execStart 的 HTTP upgrade 内部使用无时限但可中断的 latch.await，中断路径不会关闭 channel，且收到响应前 callback 没有 closeable。池内增加一个共享的单线程超时定时器，仅负责到期打断当前 Worker 的命令启动等待，不接收判题任务、不改变任务调度。启动阶段以 cleanupTimeoutMs 为有界响应等待；启动正常返回后先关闭该作用域，再按编译/运行配置等待输出完成。未收到启动响应的超时为系统失败，启动后的执行等待超时为 OUT_OF_TIME。极短编译超时测试在收到启动响应后才开始计算编译等待。

作用域退出与定时打断在同一锁内协调，取消定时项并清除自身产生的中断，避免打断下一任务；关闭/外部停止必须先写 Worker 停止标记，不能只凭中断位区分来源。定时器启用 removeOnCancelPolicy，关闭池先禁止新作用域并停止 Worker，最后有界关闭定时器，拒绝调度不得降级成无超时执行。启动异常时关闭该 Worker 独占的 Netty 客户端，回收没有 onStart 句柄的 channel，不关闭其他 Worker 的客户端。真实无响应 upgrade 测试必须观察服务端 EOF（允许 Netty graceful shutdown 的短暂收尾期）、迟到 onStart 被关闭和线程最终退出，不能只检查 Future 返回。

stats 异步请求同样在启动预算内确认 onStart；未取得关闭句柄或启动失败时，整次任务按系统失败淘汰 Worker，不在业务正常结束后留下不可关闭的 stats 请求。已关闭回调收到迟到 onStart 时立即关闭新句柄。

内部 SandboxTaskOutcome 包含业务结果、discardContainer 和原因。Future 保持原 SandBoxExecuteResult 类型。编译/运行等待超时用 OUT_OF_TIME 加明确文案，输出超限用 FAILED，普通编译失败 COMPILE_FAILED、普通运行失败沿用 NOT_ALL_PASSED，无法确认的通信/回收失败为 UNKNOWN_FAILED。正常退出 0 时 stderr 仅作诊断。

## 资源生命周期与任务完成

每个已预留名额有本实例资源记录，记录 name、id（可暂缺）、core、目录、生命周期与 Worker。创建前先预留，CREATING、ACTIVE、RETIRING、QUARANTINED 都计入最大数。生命周期锁只保护状态和队列操作，避免持有全池锁等待 Docker I/O。每个资源回收幂等，DESTROYED 只能在移除确认及目录清理后写入；不能永久积累已销毁 ID。

启动先检查本池完整名称边界（prefix 或 prefix- 开头），发现遗留容器立即失败，不接管。新容器带池名与实例 UUID 标签。创建返回 ID 前发生异常时，按精确名称及本实例标签尝试定位；无法确认创建结果则保留隔离名额与名称，不假定没有创建，也不删除其他实例资源。创建/启动/配置核验失败同走回收。

Worker 的执行流程为：执行 → 若可复用则确认容器仍运行并清理文件 → 完成 Future；若淘汰则先停止接任务 → 强制 remove(force) → inspect 确认 NotFound → 清理目录 → 完成 Future → 核心资源单次补建。回收失败当前任务改为系统错误，记录隔离，不自动重试。仅“文件系统/容器不存在”的确定结果可当作已经清理，超时或通信失败不能当作成功。

核心补建期间保留恢复名额，防止临时扩容抢占后超过 max-size；成功后启动新 Worker。补建失败释放已确认无残留的预留或保留不确定资源，记录容量降低。所有活动/恢复资源均不可用时，在同一生命周期锁内阻止入队并取出待执行队列，逐一返回 SYSTEM_BUSY。临时容器空闲回收不补建。关闭先禁止提交及补建、拒绝队列、通知 Worker，再走同一回收流程；中断标记不能导致跳过 Docker 清理或重复释放名额。

## 文件边界

每个容器有明确目录。创建前核验根目录和名称，拒绝符号链接根目录。首次创建的容器目录必须由本次创建操作独占建立；已有同名目录即使为空也不自动接管或删除。资源记录区分目录是否由本轮创建。写 Solution.java 使用 UTF-8 且不跟随链接。清理不按扩展名筛选，递归删除所有任务文件，正常时保留挂载根。异常时确认容器消失后再删目录。

使用不跟随符号链接的目录操作，优先使用 SecureDirectoryStream 进行相对目录句柄删除，防止中间路径链接替换影响外部目录；不支持安全目录流时失败隔离，不使用可能跟随链接的递归兜底。每次访问/删除检查单调时间预算，超时或 I/O 失败不允许复用。预算不宣称能中断宿主机内核中不可中断的文件系统调用。

## 验证与环境

JUnit/Mockito 覆盖限制、UTF-8 和累计输出、超时/断流、生命周期竞争、容量和清理失败、命令超时及现有幂等语义。真实 Docker 测试直接调用池化服务，使用 JDK 17、azul/zulu-openjdk:17、唯一前缀与本轮目录，逐项验证用户计划的场景；容器池原测试同时更新为新接口和实际准入条件。控制请求超时使用受控无响应 HTTP 端点，不能只以抛异常 Mock 冒充网络超时测试。

本机默认 JDK 25，构建及测试使用已有 maven:3.9.9-eclipse-temurin-17。构建副本与运行目录在容器内按宿主绝对路径挂载，使兄弟沙箱容器绑定同一路径；挂载 Docker socket 使用真实本机 Docker，新增资源均登记。首次测试前保留容器、镜像和 Git 基线，不启动数据库/MQ或修改服务配置。

## 证据与交付

风险 strict，机器核验。OpenSpec CLI 扩展元数据使用 x-workflow-profile、x-verification-mode，分别映射 strict、machine；证据助手显式传入 profile 并绑定 metadata，不修改共享模板。规划评审和独立代码评审必须真实执行。

原始命令、JUnit、容器配置及事件记录先放本轮目录，结束前整理到最终 Markdown 报告附录，使清理后的证据索引只引用保留报告及正式交付文件，不引用已删除临时日志。用户要求清理的运行目录、独占新增镜像和容器在报告生成后按归属删除，原有报告保留。性能不作新结论，正常退出主动遗留后台子进程不宣称完整解决。
