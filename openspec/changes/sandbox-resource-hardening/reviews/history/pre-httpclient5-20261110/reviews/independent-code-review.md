# 独立代码质量评审

- 变更：sandbox-resource-hardening。
- 风险等级：strict；核验方式：machine。
- 独立评审者：`/root/sandbox_review`。本执行上下文未编写或修改业务代码及测试，只进行了独立评审、反馈缺陷和编写评审报告；实现与修复由主执行上下文 `/root` 完成。
- 评审时间：2026-11-10T07:27:46.063888+00:00。
- 代码基线：Git 46cdb8afe4683e4f9b45eff1b173e7d8bbc54b22 `46cdb8afe4683e4f9b45eff1b173e7d8bbc54b22` 加下表 33 个模块修改/新增文件；全部文件与 `docker-full-02` 的实际测试源码快照相符。
- 结论：**PASS：当前实现和已完成测试范围内未发现未解决的阻塞缺陷。**本结论是独立代码质量评审，不替代运行资源最终清理、完整需求映射核验或主验收结论。
- 已遵循用户范围：不加入接口或用户限流，不修改非池化执行，不切换全部执行流到 HttpClient5；使用共享 HttpClient5 控制客户端、每 Worker 独占 Netty 流客户端和一个启动超时定时器。

## 本次核查范围

审阅容器池的创建、准入、名额、排队、回收、补建、关闭与 Future 收敛；文件归属、符号链接及深度/时间预算；编译/运行和统计流启动、输出累计、退出码与错误分类；超时定时器取消及生命周期；配置与内部题目时限参数；相关原有回归和新增测试。未修改已绑定规划报告。

生命周期锁只保护队列及资源状态，实际 Docker 回收在资源锁内完成；未发现当前正常路径存在全池锁与资源锁的相反等待环。创建不确定时保留隔离名额，回收必须确认旧 ID 不存在，核心恢复中的 RETIRING/DESTROYED 状态不被误判为全部资源失效。关闭先禁止提交及新超时作用域，再停止 Worker、回收及关闭定时器。正常与异常结果均在所需文件清理/回收完成后才完成 Future。

超时定时器只中断启动等待，作用域关闭和到期动作由同一锁协调；执行等待另用有界 latch。输出回调关闭、迟到 onStart、错误优先级与字节预算分离。SDK 升级中断不能自动关闭 channel 的缺口，通过异常 Worker 的专属 Netty 客户端关闭收敛；不会关闭其他 Worker 的客户端。

## 发现、修复与复核

| 发现 | 当前修复 | 复核证据 |
|---|---|---|
| 用户深目录可能耗尽宿主递归栈与目录句柄 | 深入前限制 64 层，超限抛 IOException，隔离且保留名额，Future 正常结束 | `SandboxFileManagerTest.deepUserDirectoriesFailWithIOExceptionInsteadOfExhaustingHostStack`、`DockerSandBoxPoolLifecycleTest.deepUserDirectoryStillCompletesFutureAndKeepsQuarantineSlot` |
| 一个核心回收失败时，另一个仍在回收的核心被忽略，导致过早拒绝队列 | 核心 RETIRING 及回收成功待预留替代的 DESTROYED 状态计入可恢复资源 | `anotherRetiringCoreCanStillRecoverQueuedTasks` 使用双 Worker 与闩锁覆盖失败/成功交错，队列最终成功执行 |
| 完整 stderr 超过既有 exe_message varchar(500)，将普通错误变成持久化错误 | 失败诊断限制为 500 个 Unicode code point，包含截断标记且不截断代理对 | `longFailureDiagnosticsFitExistingDatabaseColumnWithoutSplittingUnicode`，真实普通异常与 StackOverflowError 也验证长度及原容器复用 |
| Files.exists=false 可能表示 I/O 不确定，而非确认已删除 | 仅将 readAttributes 的 NoSuchFileException 视为已不存在，其余失败向上传播并隔离 | 静态检查 `SandboxFileManager.clean` 的异常分支，结合已存在/链接/预算失败的回归测试 |
| JDK 17 编译及默认输出可能依赖 locale | 显式 `javac -encoding UTF-8`、`java -Dfile.encoding=UTF-8`，字节合并后统一 UTF-8 解码 | 实际中文源码、emoji、跨帧大输出及符号链接目标保护测试 |
| 原生客户端加载失败属于 LinkageError，可能逃过普通异常清理 | 初始化、创建、扩容、补建、回收及任务执行路径纳入 LinkageError，补齐当前 ARM64 Linux epoll 依赖 | `missingNativeClientLibraryCannotLeakPartiallyCreatedContainer`，当前 JDK 17/Linux ARM64 构建和真实 Docker 验证 |
| Netty 无响应 upgrade/stats 在 onStart 前没有关闭句柄，仅退出等待会泄漏连接 | 有界启动与 Worker 专属流客户端关闭，已关闭回调拒绝迟到句柄；取消定时项及时移出队列 | `SandboxTransportTest` 用真实 TCP 无响应端点观察客户端 EOF 和 Netty 线程消失，`SandboxTimeoutGuardTest` 验证取消/到期不误中断后续工作 |

以上发现均已反馈给实现者，并核对实际修复及对应最终有效测试，没有把“建议已提出”当作修复完成。

## 实际测试证据

保留证据统一引用：[容器池沙箱防护实施与测试报告](/Users/lllyw/lyw-oj/.codex/reports/sandbox-resource-hardening-20261110-060034-2188d02b.md) 的“最终测试结果”和“原始证据附录”。本评审已读取原始命令、JUnit 与来源快照，并逐项确认两批成功运行的全部 JUnit、命令和来源快照已原样嵌入该保留报告，不以即将删除的临时路径作为唯一依据。

| 批次 | 实际范围 | 测试数 | 失败 | 错误 | 跳过 | 命令退出码 |
|---|---|---:|---:|---:|---:|---:|
| unit-full-01 | 配置、文件、回调、执行、定时器、生命周期、容量、已有判题幂等/消费者；含 3 项真实 TCP 传输故障测试 | 50 | 0 | 0 | 0 | 0 |
| docker-full-02 | 容器池调度/回收及真实 Java 编译运行、长题时限、超时、线程限制、输出限制、文件和跨 Worker 隔离 | 17 | 0 | 0 | 0 | 0 |

两批源码快照只有 `SandboxSafetyIntegrationTest.java` 不同，所有生产源码、构建配置及单元测试源码不变；最终 33 个修改/新增模块文件均匹配 docker-full-02 快照。因此单元/TCP 批次可复用，无须为了同一生产基线重复运行。

首次 docker-full-01 有 1 项失败，原因是测试将 cgroup memory.max 与未按页对齐的请求字节数直接比较。已核对修正为读取容器实际 `getconf PAGESIZE`，按向下页对齐计算期望值，并额外断言 Docker HostConfig memorySwap；这没有放宽生产内存配置。当前真实记录为 cpu.max=`100000 100000`、pids.max=`128`、memory.max=`99999744`、memory.swap.max=`0`、页大小 `4096`，HostConfig memory/memorySwap 仍为 `100000000`。

两种线程创建程序均记录了 `current=128` 和 `events=max 4`，证明内核 PIDs 限额实际拒绝创建，未把单独的 native-thread OOM 文案当证明。运行/编译超时后的旧容器 ID 不可再查询；后续正常 A+B 能执行。两个 Worker 并行的真实场景证明淘汰一个 Worker 不会关闭另一个的执行流。

删除失败的真实容器测试使用真实 Docker 容器和针对 remove 命令的故障注入，验证隔离、名额保留和后续拒绝；测试最后用独立控制客户端清理本测试资源。它不声称真实 Docker 服务曾自然发生故障。真实 TCP 测试的服务端只有 read 返回 EOF 才认定客户端关闭，自身 finally 关闭连接不能冒充通过。

## 剩余边界与交付事项

- 依据已确认范围，未增加用户/接口限流、完整后台子进程追踪、自动恢复系统或新的判题调度器；普通返回后主动遗留且不持有当前输出流的后台进程不宣称完整防护。
- 内存资源限制已实际核验，但 `getMaxUsage` 的历史峰值/单位口径未在本次修改，不据此宣称判题内存统计已经准确。
- 大输出的沙箱缓冲被限制为 1 MiB，但现有 `case_judge_res TEXT` 约 64 KiB 的完整业务存储上限仍在；本轮没有重新启动 HTTP、数据库或 MQ，不宣称大输出的完整业务持久化链路已经验证。500 字符诊断摘要修复的是明确的异常信息长度问题。
- 文件递归的深度和时间预算用于有界失败隔离；深度超限或无法安全清理时可能保留隔离目录/名额，符合已确认失败策略，不自动放宽限制。预算不能中断内核中不可中断的文件系统调用。
- 验证环境是 JDK 17、Linux ARM64、Docker Desktop cgroup v2；其他操作系统、架构及实际生产部署没有实测。当前新增原生依赖面向 Linux ARM64，不能把本次结果推广为所有 Unix Socket 平台都已验证。
- 运行资源最终清理、Git 改动范围复核、验收清单状态及最终指纹由主验收流程处理；本报告引用的保留报告清理小节应以实际完成记录为准。本独立代码结论不授权归档、提交、推送或部署。

## 当前模块基线 SHA-256

以下清单覆盖当前模块全部修改及新增文件；报告本身及规划/主报告由最终验收指纹另行绑定。

| 文件（相对 /Users/lllyw/lyw-oj） | SHA-256 |
|---|---|
| `oj-modules/oj-judge/pom.xml` | `c1411f91baf20977b6b382c57bdcfe673b8f2aa5bfce160a7141ec06cd7fbaab` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/callback/SandboxOutputBudget.java` | `4ef293a2dc0e6cc7be12f41e3153c462875ec5b8799d2be540546c3400a9eca2` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/callback/SandboxOutputCallback.java` | `abf729fd4a5b2009eba8efebe9a4f6a92c3430a49d98e3be665002dc9ac7fef9` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/callback/SandboxStatisticsCallback.java` | `0fcb3ed7ab9bdd6fc921ebc60bdfe1586eb0d279b54c9b5e6b77fefdc62f9060` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/callback/SandboxStreamCallback.java` | `b3f882e47fb76e496914e8cd669f15b781fadeb6baca5988a976f8cf4854ffcd` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/DockerSandBoxPool.java` | `5fdc4bd17c655c26d46f3d04ffd660640cd00f412ed6a56c92ad0d052ecec697` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/DockerSandBoxPoolConfig.java` | `9205db6ab40c44434b8d134ebccfe71ed9a9f73d362cdf87af1c76bbf405b50b` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/SandboxDockerExecutor.java` | `9e8f57f90174c46ffa16be4008f485d2390d3f08102806a5b18d024fecbc3c2f` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/SandboxFileManager.java` | `30db8a65a5547cf10a9cca98585c42a2898b2b501dbc15662d11793bf6cae712` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/SandboxLimits.java` | `312f742ac756b2d24ef6c49a7cc19a9bca866e846baf414127a3f66ce76b167c` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/SandboxStreamClientFactory.java` | `0e38516e295882661a5c41f0cde94cb4ac0cb4ec60ab774a37dea7ebc82102ee` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/SandboxTimeoutGuard.java` | `277fb5816c1a2ed049ff5e3893b96169d77c113650ac06852a70f5e719aa6be3` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/domain/JudgeTask.java` | `2cd2b937181fe0c0692c2b57cd0aada4898157da3e922fd9175833d0bcaee91b` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/domain/SandboxTaskOutcome.java` | `c15036f4fe391c420eba132ceca92a8cdde7b7f37a57c6432bd0909880c10e8f` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/service/ISandboxPoolService.java` | `c85f6b2e593acde9d8201a78187581824bb264f3503f6654f8c0fe32bfe078bd` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/service/impl/JudgeServiceImpl.java` | `1aca98f059a0556f0df4fee60938b6c29aab65d2654753af1eb3d706484d30ec` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/service/impl/SandboxPoolServiceImpl.java` | `e75d4c6cefeef80d238bd96c4251282db5d23879b64aca07fa7cdca8c8f80bbd` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/callback/SandboxOutputCallbackTest.java` | `ae146395b4012b881abcc7aaa1e4f89debb6ec1d0019fa23bde2a7ff0994a9ed` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/DockerSandBoxPoolConcurrencyTest.java` | `8bec46598f3f0907278f9e1215e223f15a016075eb09fe15a2da431acadf1fde` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/DockerSandBoxPoolIntegrationTest.java` | `5deadac13a546ddd46c76eea4768824228377e605fc2f36706b4d00bd855cb37` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/DockerSandBoxPoolLifecycleTest.java` | `df6c21cfe9618eb48fc34d09f86b3bdbba29e2d1feb1c29db7a6778470670c88` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/FakeDocker.java` | `42180c7ca89fd53ad952181b9bd3b4af1125078352b43e5868fc75789c83b163` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/SandboxDockerTestSupport.java` | `de71919090f87a3841535718a84114af33c703e3018d1ec41660478c56d3f482` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/SandboxExecutionTest.java` | `2b31996d54f1c3a7d040b66db27600afa92b76e1bdcb5cbd91d940dade545c54` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/SandboxFileManagerTest.java` | `0b2f1759871f7be82a67c4fb059d4d052467ff5ba39dfdaa7707f0302273871c` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/SandboxLimitsTest.java` | `d7771f6c82fe3a96966df4633a2c1ff528c6af3a52f5402d9e738f53fe2fa8b9` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/SandboxSafetyIntegrationTest.java` | `ff01a392055729b4d3239ad2efc8a7a4297b7a703553301eb110d83ca19b7241` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/SandboxTimeoutGuardTest.java` | `08a6400ae7d1a2dcfe6e5963d2e68084c909def929ffe36af76a14e24cfc08a9` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/SandboxTransportTest.java` | `6362696c89954dbfa3b1eac12067ebf27758626f49c143392b2fa8735dcef27c` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/domain/JudgeTaskTest.java` | `d7cf53982a332a6bac49a091c108cf7532b6e380e04f4f426f53bd41da877fe9` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/service/JudgeServiceIdempotencyTest.java` | `22bb02d40843987c384eedf6eaa454f47a9033a45ddab416b477a579d68c75c4` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/service/JudgeStorageIntegrationTest.java` | `8a0ed7ea13009dd88e93cfeeaeae11507e50dd894b33823abcc532748c0d7f72` |
| `oj-modules/oj-judge/src/test/resources/logback-test.xml` | `173e8c2718dd284dbdcf09f8902ad225bb6f32acebf0d970afc4df9079ee4331` |
