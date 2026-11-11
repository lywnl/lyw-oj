# 独立规划评审

- 变更：sandbox-resource-hardening。
- 风险等级：strict；核验方式：machine。当前 CLI 元数据以 `x-workflow-profile: strict`、`x-verification-mode: machine` 映射项目语义，证据助手由主执行者显式传入 profile 并绑定 metadata。
- 结论：PASS，可按当前规划进入实施；本结论不表示实现或真实 Docker 验收已经通过。
- 独立评审者：`/root/sandbox_review`，独立子执行上下文；未撰写所评规划，未修改业务代码，仅写本报告。规划作者为主执行上下文 `/root`。
- 首次评审时间：2026-11-10T06:09:21.080501+00:00。
- 最近聚焦复核时间：2026-11-10T06:16:43.802025+00:00。
- 授权根：`/Users/lllyw/lyw-oj`。用户附件位于获准读取的 `/Users/lllyw/.codex/attachments/`，已逐字节核对工作区计划副本与附件一致。

## 检查结论

1. 范围与用户最后指令一致：只实施四项池化防护，不加入限流、不合并非池化、不改内存统计口径，也不宣称解决正常退出后主动遗留后台子进程的完整隔离。
2. 资源限制、题目时限传递、编译/运行等待、输出累计预算均有可观察要求和测试任务。将业务结果与容器处置分开，能够在保留真实判题结果语义的同时可靠淘汰问题环境。
3. 创建前预留名额，回收中与隔离状态计入容量，核心补建保留恢复名额，能够修复原实现提前释放容量和失败后重用同名容器的组合缺陷。关闭、入队及补建的竞争已列入任务。
4. “确认容器消失与目录清理后才完成 Future”的顺序已明确；回收失败返回系统错误、无活动或恢复资源时拒绝队列，能够避免伪造用户答错记录和永久挂起。
5. 文件处理采用有归属的目录、独占创建、不跟随链接及安全目录句柄。清理预算明确不宣称中断内核中的不可中断文件系统调用，与实现能力边界一致。
6. 已有池化集成测试的 `task()` 主要返回受控结果，不能替代实际 javac/java 验收；本规划 4.2 已要求直接调用真实池化服务并记录容器与文件证据。控制超时使用受控 HTTP 端点，真实测试与 Mock 边界清楚。
7. 用户要求清理临时运行痕迹、保留报告，设计以报告附录保存原始命令/JUnit/资源证据并重定位索引，避免清理后留下失效证据引用。规划与正式测试源码作为交付保留，不与运行痕迹混淆。

## 发现与处置

无未解决的规划阻塞，无需额外用户业务决策。

- **目录归属细节已补足。**初读设计只写了目录及链接核验，尚未明确处理已存在的同名目录。已反馈给主执行者；当前 design.md 的“文件边界”明确首次容器目录必须由本次独占建立，已有同名目录即使为空也拒绝接管或删除，资源记录区分 directoryOwned。本报告绑定修订后的设计哈希。
- **创建超时的暂时 404 不是可靠清理证据。** design.md 的“资源生命周期与任务完成”已有“无法确认创建结果则隔离保留名额”约束。主执行者已确认，尚未拿到 ID 的创建请求超时后，即使按名查询暂时 404 仍保留不确定名额，不推断请求不会稍后完成。该点属于任务 3.1 的实现验收重点。
- **连接/启动阶段也不能悬挂。** design.md 的“执行限制与接口”已补充启动等待的定时中断和按 Worker 独占的 Netty 客户端。第二轮复核确认 SDK 升级中断不关闭 channel 的缺口，最新方案已明确对应的关闭归属，详见下节。

## 实施与验收重点

- 对已发出的创建操作，区分“确定未创建”“已取得本实例 ID”“结果不确定”，仅回收可确认归属的对象。故障注入至少覆盖迟到创建成功及首次查询不存在，不能只覆盖立即抛错。
- 回收顺序使用事件或断言证明：删除确认、目录清理先于 Future 完成；补建在其后。关闭路径、临时 Worker 超时及执行异常复用同一名额处理，不能依赖 finally 再执行一套不受控删除。
- 限额核验同时检查 Docker 实际配置和运行证据。线程创建失败可能由内存先耗尽触发，不能仅凭 native-thread OOM 文案断言 PIDs 限额生效；CPU 也应核验配额/节流，而非把一个瞬时统计样本当完整证明。
- 输出预算在编译、全部用例及两个流间共享，拒绝超限后继续累加。覆盖刚好达到上限、跨帧中文、stdout/stderr 交错、空输出及回调晚到关闭。
- 正常错误复用要确认 exec 已退出并且清理成功；源码和编译编码需与 UTF-8 约定一致。安全目录流不可用时应明确失败，不静默切换到可能跟随链接的清理方式。
- 真实 Docker 测试用唯一前缀、绝对一致的宿主/构建挂载路径及本轮根内临时目录，记录 baseline 后只清理本轮资源；不要读取或改动其他任务的配置、容器环境或报告。
- 原始证据搬入最终报告后，验收清单、指纹与报告应引用保留位置，不能仍引用将删除的独立 XML/日志或本轮构建副本。

## 超时实现细节聚焦复核

结论继续为 **PASS**，针对补充后的设计进行真实复核，非仅刷新哈希。一个共享定时器只为当前操作提供到期中断，不接收判题任务，不改变既有队列或 Worker 调度，属于兑现已确认有界执行的实现细节。

### 已发现并处理的 SDK 关闭缺口

独立读取 docker-java 3.3.4 官方源码，确认 `NettyInvocationBuilder.post(entity, stdin, callback)` 在发送请求后直接调用 `hijackHandler.awaitUpgrade()`，周围没有中断清理的 finally；`HttpConnectionHijackHandler` 仅将 InterruptedException 包装为 RuntimeException。`HttpResponseHandler` 在收到 HttpResponse 后才通过 `onStart(() -> channel.close())` 暴露关闭句柄。因此，只中断 Worker 并调用尚未取得句柄的 callback.close，不能关闭永不响应的升级连接。

该问题已立即反馈。最新 design.md 选择共享有界控制客户端、每个 Worker 独占 Netty 执行流客户端：正常任务复用该客户端，启动异常或 Worker 退休时关闭其所属客户端，使 SDK 的 event loop/channel 进入关闭流程，不关闭其他 Worker 的客户端。该所有权划分能在请求尚未暴露句柄时提供关闭路径；设计层面的缺口已解决。是否实际观察到 EOF、线程退出及无跨 Worker 误伤，仍是后续真实验收门槛，不在本次声明完成。

官方来源（本次只读核实，未修改第三方代码）：

- https://github.com/docker-java/docker-java/blob/3.3.4/docker-java-transport-netty/src/main/java/com/github/dockerjava/netty/NettyInvocationBuilder.java
- https://github.com/docker-java/docker-java/blob/3.3.4/docker-java-transport-netty/src/main/java/com/github/dockerjava/netty/handler/HttpConnectionHijackHandler.java
- https://github.com/docker-java/docker-java/blob/3.3.4/docker-java-transport-netty/src/main/java/com/github/dockerjava/netty/handler/HttpResponseHandler.java

### 安全关闭与测试约束

1. **作用域隔离。**定时触发和作用域关闭以同一锁确定谁先完成；启动正常返回先取消/关闭启动作用域，再开始编译或运行等待。旧定时项不能中断 inspect、回收、下一用例或下一任务，迟到 onComplete 不能覆盖已经确定的超时/输出超限结果。
2. **中断来源。**Java 中断位没有来源信息；外部停止必须先设置 Worker 生命周期停止标记，再中断。作用域不得把清除中断位等同于撤销外部停止；回收不能因未处理的中断位被直接跳过。
3. **定时器生命周期。**启用 removeOnCancelPolicy，取消后的长时限项及时移出队列；关闭先禁止新作用域并停止活动 Worker，最后关闭定时器且有界等待终止。调度被拒绝时停止执行，不能退化为无限等待。
4. **无句柄请求。**异常 Worker 的专属客户端必须关闭，不能通过关闭共享客户端误伤其他 Worker。普通回调在 close 后收到迟到 onStart，也必须立即关闭该句柄且不重新开放结果缓冲。
5. **stats 同样适用。**stats 是异步请求，用户 exec 已完成不能证明 stats 已取得可关闭句柄。应对其启动作有界确认，或在无法确认请求已关闭时淘汰所属 Worker 并关闭专属客户端；不能因为业务成功就留下永不响应的 stats channel 后继续复用。该约束已由主执行者补入最新 design.md，明确 stats 有界确认 onStart、无法取得句柄或启动异常时淘汰、迟到 onStart 立即关闭句柄；落实既有“所有路径关闭 exec/stats”要求，不增加统计口径或任务调度功能。
6. **区分两类超时。**启动等待使用 cleanupTimeoutMs，未取得正常启动响应按系统失败；正常启动后按编译/运行限额计时。极短真实 javac 测试必须确认启动阶段完成后才验证 OUT_OF_TIME，不能以 UNKNOWN_FAILED 冒充编译超时。
7. **真实验收。**无响应升级端点需观察服务端 EOF，并检查迟到 101/onStart 的关闭；覆盖 stats 无响应、正常完成与定时触发竞争、池关闭与作用域竞争、连续故障重建后线程/连接收敛，以及另一个 Worker 的正常执行不受影响。仅 Future 返回或 close 方法返回不是连接/线程结束的充分证据。

## 本次验证范围

本评审读取规则、当前规划、相关源码及已有测试，并执行只读 OpenSpec 结构校验；没有运行业务测试、创建或操作 Docker 容器，也未验证将来的实现。

- 命令：`openspec validate sandbox-resource-hardening --strict --no-interactive`。
- 工作目录：`/Users/lllyw/lyw-oj`。
- 退出码：0。
- 原始输出：`Change 'sandbox-resource-hardening' is valid`。
- `openspec status --change sandbox-resource-hardening --json` 确认四类规划产物已齐备；CLI 的 complete 状态不作为代码完成或验收通过依据。

## 基线 SHA-256

以下哈希用于识别本次所评内容；不替代主执行者的 record-plan/check-plan/start-apply。

| 文件（相对授权根） | SHA-256 |
|---|---|
| `openspec/changes/sandbox-resource-hardening/.openspec.yaml` | `269801709915a5c0666ed7e7758feed8e250bdbc7e186b5fd146d05d93d64f40` |
| `openspec/changes/sandbox-resource-hardening/proposal.md` | `68ea4e0fbafb4c57898fb5d6def638c2cc21c3a710f9a9f1bededb668299dbac` |
| `openspec/changes/sandbox-resource-hardening/specs/judge/container-pool/spec.md` | `a80ea53a751da3ba7fbe8522ddc9d0997d901f38b03ae7618b0923a7b7b69960` |
| `openspec/changes/sandbox-resource-hardening/design.md` | `1596afd74e96c7289ec795f7864e1c64dc78481826200eb098e2986628d92a40` |
| `openspec/changes/sandbox-resource-hardening/tasks.md` | `2517c53da8d3adf16f1008965aec0a38eb0ece4478680825d758c7cc60cfcd4f` |
| `.codex/plans/容器池沙箱防护执行计划.md` | `b7d4be6d6622ed920322bae07fc0eea888d2cec032ddb16091db72562e077c52` |
| `AGENTS.md` | `20357a5428a4639631d2cccf23c6b9eb47b615960acc1f30db1a77c7f4bb4409` |
| `.agents/skills/openspec-review-change/SKILL.md` | `5274cb3aeb7db1ff741925013940778134bc493aca77ed25b9b1c7c2d3aeba76` |
| `.agents/shared/openspec/workflow-policy.md` | `8a324454d91d27dda2fbf140198cbbc7413c3ca3fd9a1af75553b12517b60d1d` |
| `.agents/shared/openspec/review-checklist.md` | `f9b0b88624f17a916b52c027ec9490abc64484ad935b33dc24df36a33a76a3fa` |
| `.agents/shared/openspec/verification-evidence.md` | `6061d192eefbb4b0e8207b36d78a0ebb1aef7904f5d9caf6a5fbd52cb600cd19` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/DockerSandBoxPool.java` | `7ccb7b4f9fb79afc9f9780a392bab82b44c31ad40d7a926b4ccaa082203147b3` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/config/DockerSandBoxPoolConfig.java` | `96028bc0b2addb7d460a406d42bd17f2f9a1910ed3379f65e8c23d7cb6cf9388` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/service/impl/SandboxPoolServiceImpl.java` | `8c95d2bb71901d8c869410833bd49ae3e7b783f5c40241503a7aeed342f1cdad` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/domain/JudgeTask.java` | `c4b670c2dac8b1f9cc057398fe8a12b5c560643c0b5c618e10e9e5ed4e0b1e32` |
| `oj-modules/oj-judge/src/main/java/com/lyw/judge/service/impl/JudgeServiceImpl.java` | `cf9667dcfe644af9aed781b99958201461542bcf1588d59c88900af30d67ed04` |
| `oj-modules/oj-judge/src/test/java/com/lyw/judge/config/DockerSandBoxPoolIntegrationTest.java` | `2b9256a825b3a5951021737cd3449d67f9f5871ccbc4be2e09ea6803acb44e95` |
| `oj-modules/oj-judge/pom.xml` | `3a07df13a53feaf1b6eb51726aa5ca6e57189742d316b1d74c6505eaf1bddafe` |
