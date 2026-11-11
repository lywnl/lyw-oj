# 设计

## 边界与现状

以用户原文计划为约束，只改池化路径。现有 Worker 对容器执行独占任务，但健康检查只看 Running，编译无超时，运行超时仅删除源码，销毁前释放名额且提前写销毁标记。已有幂等和系统错误语义继续保留；不修改未归档的其他变更及其证据。

## 执行限制与接口

集中提供不可变 SandboxLimits：memory=100000000、swap=100000000、cpu=1、pids=64、compileTimeoutMs=10000、minimumRunMs=5000、runGraceMs=1000、outputMaxBytes=1048576、cleanupTimeoutMs=10000。沿用用户计划的配置键，正数及 long 换算检查。内部池化 exeJavaCode 增加 Long questionTimeLimitMs；调用方在入队前校验并计算 max(minimumRunMs, addExact(questionTimeLimitMs, grace))，外部消息不变。

根据用户“收紧到64”的最新指令，同步 Spring 配置回退值与 SandboxLimits.defaults() 为 64；保留显式配置覆盖。默认相关真实 Docker 断言改为 64，自定义编译/输出限额的真实测试同样取当前默认 PIDs，避免混入 128 的通过结果。其他数字 128 若是单元测试主动指定的合法自定义值，不做无关替换。重新验证正常编译运行、内核拒绝与回收，旧报告继续保留为历史。

编译及运行通过一个受限执行辅助方法执行。控制与执行流均采用 docker-java 3.3.4 的 HttpClient5。共享控制客户端负责 create/inspect/remove 等短请求，连接 3 秒、响应等待 cleanupTimeoutMs；工厂为每个 Worker 创建独占的 exec/stats 流客户端，连接 3 秒、responseTimeout=Duration.ZERO，正常任务复用，退休时关闭。客户端实例与生命周期仍明确区分，不能给执行流套用控制请求的短响应超时。Docker API 版本沿用可配置能力，真实测试用服务端支持的 1.41。

池化专用回调使用任务共享字节预算和有界字节缓冲。超限仅设置标记并唤醒 Worker，回调线程不阻塞执行 Docker 回收；Worker 关闭流并淘汰。输出原始字节合并后按 UTF-8 解码，stdout/stderr 均计入预算。exec 的正常完成、错误、超限分开表示；只有 inspect 确认 Running=false 且取得退出码才认定命令退出。stats 流显式关闭，继续使用现有 getMaxUsage 统计口径。

用户已明确确认将执行流改为 HttpClient5，覆盖原设计保留 Netty 的选型，来源见 `.codex/plans/容器池沙箱防护HttpClient5选型确认.md`。已核对 docker-java 3.3.4 的 DefaultInvocationBuilder.executeAndStream：流请求在 SDK 自有 daemon 线程异步启动，调用方不阻塞在 HTTP 响应头等待。因此移除 Netty 专用 SandboxTimeoutGuard 及其定时器，不增加新的判题调度器。

执行流程保持“有界启动 → 有界执行等待 → 退出码核验”。调用 exec/stats 后用 callback.awaitStarted(cleanupTimeoutMs) 确认启动；未收到响应头时按系统失败淘汰，由 Worker 关闭独占 HttpClient5 客户端使挂起连接和 SDK 流线程退出。取得 onStart 句柄后，正常/异常路径关闭 callback 以中断对应流线程并关闭响应；已关闭 callback 遇到迟到 onStart 立即关闭新句柄。实际 HTTP response body 长时间无输出仍由编译/运行等待控制，不能误用短连接响应上限。

镜像拉取同样由 SDK 异步启动，沿用 120 秒有界 awaitCompletion，finally 关闭拉取专属客户端；不再需要额外定时打断。关闭池先标记停止并中断 Worker，沿用原资源回收顺序，移除定时器关闭分支。移除本次为池化 Netty 新增的 ARM64 epoll 依赖，保留原有非池化依赖不动。

真实 TCP 传输测试必须观察无响应头和 stats 未启动时关闭客户端后的服务端 EOF、SDK 流线程最终退出；另增加收到 HTTP 响应头后持续无输出的执行等待超时测试，确认关闭 callback 就能收敛连接。真实双 Worker 测试确认淘汰一个资源不影响另一个的长时间执行。此前 Netty 版本测试保持历史记录，不能代替新传输实现的验证。

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

JUnit/Mockito 覆盖限制、UTF-8 和累计输出、超时/断流、生命周期竞争、容量和清理失败、命令超时及现有幂等语义。移除随 Netty 定时器删除而失去对象的定时器测试，增加 HttpClient5 响应后无输出的真实 TCP 超时测试；不以跳过代替重新验证。真实 Docker 测试直接调用池化服务，使用 JDK 17、azul/zulu-openjdk:17、唯一前缀与本轮目录，逐项验证用户计划的场景；容器池原测试同时更新为新接口和实际准入条件。控制请求超时使用受控无响应 HTTP 端点，不能只以抛异常 Mock 冒充网络超时测试。

本机默认 JDK 25，构建及测试使用已有 maven:3.9.9-eclipse-temurin-17。构建副本与运行目录在容器内按宿主绝对路径挂载，使兄弟沙箱容器绑定同一路径；挂载 Docker socket 使用真实本机 Docker，新增资源均登记。首次测试前保留容器、镜像和 Git 基线，不启动数据库/MQ或修改服务配置。

## 证据与交付

风险 strict，机器核验。OpenSpec CLI 扩展元数据使用 x-workflow-profile、x-verification-mode，分别映射 strict、machine；证据助手显式传入 profile 并绑定 metadata，不修改共享模板。规划评审和独立代码评审必须真实执行。

原始命令、JUnit、容器配置及事件记录先放本轮目录，结束前整理到最终 Markdown 报告附录，使清理后的证据索引只引用保留报告及正式交付文件，不引用已删除临时日志。用户要求清理的运行目录、独占新增镜像和容器在报告生成后按归属删除，原有报告保留。性能不作新结论，正常退出主动遗留后台子进程不宣称完整解决。
