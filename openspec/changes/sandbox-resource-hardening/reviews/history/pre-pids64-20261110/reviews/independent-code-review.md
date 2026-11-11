# HttpClient5 独立代码质量评审

- 结论：**PASS**。当前实现和本轮有效测试范围内，没有未解决的阻塞代码缺陷。
- 变更：sandbox-resource-hardening；等级：strict；核验方式：machine。
- 评审者：独立子代理 `/root/sandbox_review`。未编写或修改业务代码和测试；本次只读审阅、反馈问题及编写本报告。实现者为 `/root`。
- 已核对有效 `check-plan` 及实施开始记录 `2026-11-10T08:09:54.693298+00:00`，本轮没有重复规划评审。

## 差异范围与代码结论

相对 `reviews/history/pre-httpclient5-20261110/reviews/independent-code-review.md` 的真实评审基线：**26 个文件哈希未变、5 个文件修改、2 个文件删除**，没有超出原模块文件集合的新增文件。未变部分复用该历史评审及已确认修复；当前受影响行为全部重新测试，不复用旧 Netty 的测试结果。

| 本轮差异 | 独立复核结论 |
|---|---|
| `DockerSandBoxPoolConfig` | 控制与执行流均使用 HttpClient5，工厂每次创建独立客户端。控制响应有界；流为 `Duration.ZERO`、连接 3 秒，客户端归属继续分离。 |
| `SandboxDockerExecutor` | exec/stats 由 SDK 异步发起，再有界 `awaitStarted`。启动失败抛明确 IOException，经服务/Worker 转系统失败并淘汰；正常启动后的执行等待超时仍形成业务超时。已有 try-with-resources 关闭两类回调，迟到 onStart 处理保留。 |
| `DockerSandBoxPool` | 删除定时器字段/初始化/关闭及镜像拉取 Scope；拉取仍有 120 秒等待和专属客户端关闭。shutdown 先禁止提交并中断 Worker，再回收；retire 关闭该资源流客户端、确认容器移除、清理目录，未改变 Future 和名额顺序。 |
| `pom.xml` | 保留同版本 HttpClient5，移除本轮为池化 Netty 新增的 Linux ARM64 epoll 依赖，未改非池化实现。 |
| `SandboxTransportTest` | 检查 SDK 的 `docker-java-stream-*` 线程；新增 200 响应后无正文的 300 毫秒执行超时，在关闭整个客户端前确认 callback 已断开连接。 |

`SandboxTimeoutGuard.java` 及 `SandboxTimeoutGuardTest.java` 删除符合已确认设计；原定时器两项测试被移除，新增一项传输测试，所以最终单元/组件数由 50 变为 49。源码中不再有旧 Guard 引用。

## 本轮发现与处置

唯一新发现是测试断言只接受 EOF，未识别 SDK 主动取消响应流的 TCP 复位。`unit-full-01` 为 49 项中 1 项失败；`transport-diagnostic-01` 再次真实记录 **java.net.SocketException: Connection reset**。独立核对 Apache 5.1.4 的 `InternalExecRuntime.cancel → discardEndpoint → CloseMode.IMMEDIATE` 及 HttpCore 5.1.5 的 `SO_LINGER=0`，确认该取消机制具备明确来源。

实现者只调整测试：仅响应后取消路径接受上述确切类型和文案，并以 closing 标记排除测试自身关闭；其他 IOException 仍失败。仍要求连接在整体 client.close 前终止、SDK 线程最终退出。未为通过测试而改变生产超时或关闭逻辑。最终完整单元重跑通过；失败与诊断原文保留在下述报告。

## 当前有效测试与证据

唯一保留证据入口：[本轮最终报告](/Users/lllyw/lyw-oj/.codex/reports/sandbox-resource-hardening-20261110-073207-b37b2e85-httpclient5.md)。已逐字核对两批成功日志、命令、全部 JUnit、源码快照及 Docker 事件已嵌入报告，不以待删除运行目录作为唯一证据。

| 批次 | 测试数 | 失败 | 错误 | 跳过 | 命令退出码 |
|---|---:|---:|---:|---:|---:|
| unit-full-02 | 49 | 0 | 0 | 0 | 0 |
| docker-full-01 | 17 | 0 | 0 | 0 | 0 |

两批完整 **325 项输入快照逐项相同，并全部匹配当前工作区**。两份快照 SHA-256 均为 `2b44efdce63308ed42a625e5a444c754a4afb89b6149e06a5d3e8f0902be1240`。实际 JDK 17.0.15/Linux aarch64，docker-java 3.3.4、Apache HttpClient 5.1.4、HttpCore 5.1.5。

真实 TCP 记录为 3 次 EOF、1 次明确 Connection reset，4 项均检查相应关闭效果。真实 Docker 17 项覆盖正常/错误退出、长题时限、编译与运行超时、线程及输出限额、回收与跨 Worker 隔离。PIDs 证据两次均为 `limit=128,current=128,events=max 4`；内核 CPU 为 `100000 100000`，内存按 4096 字节页对齐为 99999744，swap 为 0，Docker 请求内存/swap 仍各为 100000000。

已读取 91 条 Docker 事件。18 组夹具记录了清理；删除失败场景明确属于注入故障，隔离名额保留并由夹具独立清理，不声称真实 Docker 自然出错。`git diff --check` 实际退出码为 0。

## 边界与主流程交接

不加入限流、内存统计口径修正、后台子进程完整追踪、HTTP/数据库/MQ 大输出链路验证或性能结论。历史文件安全、创建结果不确定、容量隔离等修复和限制继续引用旧独立报告与当前最终报告。

本 PASS 是代码质量与已完成测试范围的结论，不代替本轮资源最终清理、完整需求映射及主验收记录。清理状态以最终报告实际补记为准；报告继续保留，不自动归档、提交、推送或部署。

## 本轮修改文件 SHA-256

未变 26 个文件逐项沿用历史报告哈希；当前完整输入由保留报告的 325 项快照覆盖。

| 文件（相对授权根） | SHA-256 |
|---|---|
| `oj-modules/oj-judge/pom.xml` | `8ebe8731cd8b0133391daae47ff05b759011cc8de28000b78d5539afd87471f9` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/DockerSandBoxPool.java` | `d3ad0ff9da6139864c28148a29993aa2d1e50d19e31496061a52b2ca5678401e` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/DockerSandBoxPoolConfig.java` | `86205c9239864cc8664d05aacb685d148afe86e596b4e0580d2c2fa4bd135c39` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/SandboxDockerExecutor.java` | `05dbf6a7e26e33a5bfbb3bc98fd6844032055513aafe015df70009e79b673e11` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/SandboxTransportTest.java` | `c0e24f493cb225eca8d24d94d6cee458ebf99770cfe539d4900c544961244563` |

评审时间（UTC）：2026-11-10T08:22:50.460144+00:00。
