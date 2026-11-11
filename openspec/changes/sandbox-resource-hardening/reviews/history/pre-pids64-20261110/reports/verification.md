# 容器池沙箱防护实现验收（HttpClient5）

结论：**Ready for Archive**。本轮已完成获准的实现、测试、独立评审与资源清理；没有执行归档、提交、推送或部署。

## 基线与授权

- 变更 `sandbox-resource-hardening`，schema 为 `spec-driven`，风险等级 `strict`，核验方式 `machine`。
- 原授权为按附件完成资源限额、编译/运行超时、可靠回收、文件和输出控制，并明确不加入限流。后续用户明确选择“采用 HttpClient5（推荐）”，覆盖原设计保留 Netty 执行流的选型约束。
- 来源：[原执行计划](/Users/lllyw/lyw-oj/.codex/plans/容器池沙箱防护执行计划.md)、[HttpClient5 选型确认](/Users/lllyw/lyw-oj/.codex/plans/容器池沙箱防护HttpClient5选型确认.md)。原计划正文未改写。
- 修订已经真实独立规划复审、重新登记规划和实施入口；本次实施开始时间为 `2026-11-10T08:09:54.693298+00:00`，见 [planning-review.json](/Users/lllyw/lyw-oj/openspec/changes/sandbox-resource-hardening/reviews/planning-review.json)。此前基线保存在 `reviews/history/pre-httpclient5-20261110`，没有把旧测试冒充新选型验收。
- 当前实现仍集中在 oj-judge。相对旧版已评审实现，26 个文件内容不变、5 个文件修改、2 个未提交的新文件删除；最终模块保留 31 个相对原 Git HEAD 的修改/新增文件。当前指纹覆盖模块 pom.xml、整个 src/main 与 src/test。

## 覆盖与真实结果

详细映射统一维护在 [verification-manifest.json](/Users/lllyw/lyw-oj/openspec/changes/sandbox-resource-hardening/reports/verification-manifest.json)，包括 5 项需求、20 个场景、11 项任务，共 36 个 verified 条目。全部必需任务有相应实现、测试和真实记录。

唯一当前原始证据入口是 [HttpClient5 最终报告](/Users/lllyw/lyw-oj/.codex/reports/sandbox-resource-hardening-20261110-073207-b37b2e85-httpclient5.md)。其中原样嵌入命令、时间、退出码、所有运行的源码快照、JUnit、日志、Docker 事件和清理记录；验收清单不依赖已经删除的临时目录。

| 有效批次 | 验证范围 | 测试数 | 失败/错误/跳过 | 退出码 |
|---|---|---:|---|---:|
| unit-full-02 | 配置、文件、输出、执行、生命周期、容量、任务、幂等和消费者；含 4 项真实 TCP 故障测试 | 49 | 0/0/0 | 0 |
| docker-full-01 | 真实 Java 编译/运行、资源限额、异常回收、文件/输出和跨 Worker 隔离 | 17 | 0/0/0 | 0 |

两批共 66 项，输入快照各 325 项，逐项相同且与当前工作区一致。实际环境为 JDK 17.0.15 / Linux ARM64，docker-java 与传输模块 3.3.4，Apache HttpClient 5.1.4、HttpCore 5.1.5。JDK 17 隔离构建、测试编译、UTF-8/范围检查和 git diff 检查通过。非池化 SandboxServiceImpl 未修改。

真实验证包括默认限制下正常 A+B、普通编译/运行失败和栈溢出复用、已退出后的题目超时、允许 10 秒的题目、真实 javac 编译超时、循环/死锁/用户线程超时淘汰、PIDs 内核拒绝、输出洪水和累计边界、中文分帧与空输出、文件/链接清理、扩缩容、回收顺序及后续正常提交。

内核记录 CPU 为 `100000 100000`、PIDs 上限 128、内存按 4096 字节页对齐为 99999744、额外 swap 为 0；Docker 请求内存/swap 均为 100000000。两类线程程序均记录 current=128 和 events=max 4。配额验证没有仅依赖创建参数或 OOM 文案。

## HttpClient5 关闭与失败证据

控制与流客户端生命周期分离，流客户端由 Worker 独占。SDK 异步发起 exec/stats，有界等待 onStart；无响应头时通过关闭所属客户端收敛，收到响应后由 callback.close 关闭当前请求。移除 Netty 专用定时器及两项测试，新增一项响应后无输出的真实 TCP 验证，没有削减必要行为覆盖。

控制无响应、exec 无响应头、stats 无响应头测试均观测到 EOF。响应头后无正文的请求在 300 毫秒执行等待后超时，callback 取消发送 TCP RST；测试在整体客户端关闭之前确认连接结束，并要求 SDK 流线程退出。真实双 Worker 测试确认关闭一个资源不会误伤另一个。

unit-full-01 的 1 项失败来自只接受 EOF 的断言。transport-diagnostic-01 实际记录 `java.net.SocketException: Connection reset`；独立核对实际 Apache 版本的取消路径使用 IMMEDIATE/SO_LINGER=0。测试只对响应后取消路径接受该确切类型和文案，以 closing 标记排除测试自身关闭；其他 IOException 仍失败。生产期限和回收逻辑未为通过测试而放宽。最终完整单元回归通过，初次失败、诊断及对应源码快照均保留。

prepare-01 是切换实施前的依赖准备与跳过测试的编译记录，不计为当前实现测试通过。旧 Netty 版本的 50+17 项也未计入本次 66 项。

## 独立评审与清理

[独立代码评审](/Users/lllyw/lyw-oj/openspec/changes/sandbox-resource-hardening/reviews/independent-code-review.md) 由未编写实现的 `/root/sandbox_review` 完成，结论 PASS。未变部分复用历史代码哈希和评审，全部受影响行为以本轮真实测试重新核验，没有未解决的代码阻塞。

原始证据转存并校验后完成清理：测试 fixture 已回收本轮沙箱容器；收尾删除 5 个已退出的 Maven 构建容器、本轮独占新增的 Zulu 镜像、隔离构建/依赖/运行/研究目录及临时指针。无本轮独占资源残留。

原有 8 个容器 ID、名称和状态与基线一致，原有 10 个镜像保留，两份性能报告及旧版沙箱报告 SHA-256 不变。清理按本轮 ID、标签和挂载归属操作，没有全局 prune。当前报告、实现、回归源码、授权计划和 OpenSpec 交付材料保留，等用户明确指令再删除报告。

## 验证边界与交付

- 当前只覆盖池化链路；未加入用户或接口限流，也未实现普通返回后主动遗留后台子进程的完整追踪与隔离。
- 删除失败使用真实容器及 remove 故障注入；生命周期时序与消息分支使用 Mockito/闩锁。未启动真实 HTTP、数据库或 MQ，不宣称完整业务链路验收。
- 内存采样沿用 getMaxUsage()，没有修正 cgroup v2 下的统计口径；case_judge_res 仍是 TEXT，未验证大输出的完整数据库存储边界。真实存储测试仅同步内部接口并完成编译。
- 文件预算无法中断内核中不可中断的文件系统调用；超深目录或清理失败会隔离保留，不自动恢复。
- 验证平台为 Linux ARM64 Docker Desktop；其他架构、生产部署、高并发和性能收益未测。启动遇到遗留容器/目录仍需先核实归属，当前实现拒绝接管。

在已确认范围内，必需行为、任务、真实验证和独立评审均满足。后续归档或部署需要独立授权；回退只涉及本轮模块改动和新增文件，不涉及本轮未触碰的生产数据。
