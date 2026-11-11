# HttpClient5 独立规划复审

- 结论：**PASS**，没有阻塞实施的设计缺口；不代表新传输实现已通过运行验收。
- 评审者：独立子代理 `/root/sandbox_review`，未编写当前规划或业务代码；本次只更新本报告。
- 等级/核验：strict / machine，沿用 `.openspec.yaml` 的扩展元数据；证据助手显式使用 strict 并绑定元数据。
- 范围：只复审 HttpClient5 选型修订及其受影响验证。原范围、历史发现和旧版本结论引用 `reviews/history/pre-httpclient5-20261110/`，不重写历史记录。

用户明确选择“采用 HttpClient5（推荐）”，授权及问题原文见 `.codex/plans/容器池沙箱防护HttpClient5选型确认.md`，覆盖原计划保留 Netty 执行流的约束。资源、超时、回收、文件与输出四项范围继续有效；不加限流，不改非池化链路及外部 HTTP/MQ 契约。

## 独立核实与判断

实际只读检查 Maven Central 发布的 docker-java 3.3.4 core 与 HttpClient5 源码：

1. `DefaultInvocationBuilder.executeAndStream`（262–284 行）在 SDK 自有 `docker-java-stream-*` daemon 线程执行 HTTP 请求，调用方可立即进入有界 `awaitStarted`。移除 Netty 同步升级适配定时器具备依据。
2. 该方法在取得响应后才提供 `onStart` 关闭句柄，句柄执行线程中断及 `response.close()`；`ApacheResponse.close`（248–261 行）先 `request.abort()` 再关闭响应。没有响应头时尚无回调句柄，不能仅调用 callback.close 后复用资源。
3. `ApacheDockerHttpClientImpl.close`（199–202 行）关闭所属 HttpClient。每 Worker 独占流客户端、退休时关闭的设计，为无响应头请求提供关闭路径，并避免关闭其他 Worker 的连接。迟到 `onStart` 仍需立即关闭句柄。
4. `ApacheDockerHttpClientImpl.execute`（184–188 行）仅在 stdin 非空时设置 Upgrade；当前池化关闭 stdin，响应后无输出测试应使用普通 200 raw-stream 响应。执行流 `Duration.ZERO` 的含义是无限响应等待，由启动/执行等待及淘汰关闭共同建立上限。

附加核对：传输模块 POM 声明 Apache HttpClient 5.0.3，但历史实际 classpath 为 **httpclient5 5.1.4 / httpcore5 5.1.5**。已独立读取这两个版本的源码：`RequestConfig` 明确响应等待 0 表示无限；客户端关闭传递到连接管理器/连接池，`PoolEntry.discardConnection` 最终经 `BHttpConnectionBase.close(CloseMode)` 关闭 socket。新验收应记录当次实际解析版本。

该调用链证明设计可实施；EOF、线程退出与关闭耗时仍需真实验证。保留共享短请求客户端与每 Worker 流客户端的生命周期区分；启动失败按系统错误，正常启动后的执行等待超时按原业务超时。移除 `SandboxTimeoutGuard` 及其两项专用测试，不削减相关行为覆盖。镜像拉取仍有 120 秒等待上限及专属客户端 finally 关闭。

## 必需 TCP 验证

| 场景 | 验收条件 |
|---|---|
| 控制请求无响应 | 在控制等待范围内失败并进入既有隔离处理 |
| exec 无响应头 | 有界启动失败，关闭所属客户端后服务端连接结束、SDK 流线程退出 |
| stats 无响应头 | 不遗留挂起统计请求，不继续复用无法确认关闭的 Worker |
| 200 响应后始终无正文 | 按执行等待超时；关闭 callback 后观察服务端 EOF 或明确记录的预期复位，以及 SDK 流线程退出 |

另沿用设计中的迟到回调/关闭竞争、真实双 Worker 隔离及全部 Docker 防护回归。仅 Future 或 close 返回不足以证明连接/线程结束。旧 Netty 的 50 项单元/组件及 17 项 Docker 历史结果不能作为新传输已经通过的证据。资源回收、目录归属、创建结果不确定、容量保留和历史修复继续按原规格验证。

## 本次检查与证据边界

- 实际执行 `openspec status --change sandbox-resource-hardening --json`，工作目录 `/Users/lllyw/lyw-oj`，退出码 0；确认 `spec-driven` 和四类规划产物齐备。
- 实际执行 `openspec validate sandbox-resource-hardening --strict --no-interactive`，同一目录，退出码 0，输出 `Change 'sandbox-resource-hardening' is valid`。结构校验不代替本次语义复审。
- 没有启动 Docker、业务服务或运行测试。检查时业务工厂仍为 Netty，新选型尚未实施；此报告不能充当代码验收。
- SDK 源码来自 Maven Central 的 `docker-java-core:3.3.4`、`docker-java-transport-httpclient5:3.3.4`、`httpclient5:5.1.4`、`httpcore5:5.1.5` sources JAR；前两份从本轮研究目录读取，后两份只读入内存。

## 当前基线 SHA-256

以下为实际评审输入，不替代主执行者的 `record-plan/check-plan/start-apply`；任务文件为原始内容哈希。

| 文件（相对授权根） | SHA-256 |
|---|---|
| `openspec/changes/sandbox-resource-hardening/proposal.md` | `d240fd0ecacddbd402adff9a9f536994d6e2197d9e8f56d7d74fba40ac498426` |
| `openspec/changes/sandbox-resource-hardening/design.md` | `b5ec3329bf5ea874cb0ef2fe02f5795f3c0d3b1a2835f713b8f2fa175d0570f1` |
| `openspec/changes/sandbox-resource-hardening/tasks.md` | `87fa547d8c7455342f16505fad1971a81627be387a316d9c964369e94af1c23a` |
| `openspec/changes/sandbox-resource-hardening/specs/judge/container-pool/spec.md` | `a80ea53a751da3ba7fbe8522ddc9d0997d901f38b03ae7618b0923a7b7b69960` |
| `openspec/changes/sandbox-resource-hardening/.openspec.yaml` | `269801709915a5c0666ed7e7758feed8e250bdbc7e186b5fd146d05d93d64f40` |
| `.codex/plans/容器池沙箱防护执行计划.md` | `b7d4be6d6622ed920322bae07fc0eea888d2cec032ddb16091db72562e077c52` |
| `.codex/plans/容器池沙箱防护HttpClient5选型确认.md` | `12b0c8a657ed1a9a7ebaab76977aa6ac21ea38283758ba5842ed230218a6051f` |

评审时间（UTC）：2026-11-10T08:09:18.105977+00:00。
