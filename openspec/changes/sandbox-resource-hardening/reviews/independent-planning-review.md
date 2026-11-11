# PIDs 默认值 64 独立规划复审

- 结论：**PASS**，没有阻塞实施的规划缺口；尚未验证 64 下的实际运行效果。
- 等级/核验：strict / machine，沿用当前元数据。
- 评审者：独立子代理 `/root/sandbox_review`，未编写本次规划或业务代码；本次只更新本报告。
- 来源：用户明确要求“收紧到64”，见 `.codex/plans/容器池PIDs64确认.md`。该选择覆盖原计划默认 128；其余范围、HttpClient5 及防护行为继续引用 `reviews/history/pre-pids64-20261110/` 的有效评审。

已将 proposal、design、spec、tasks 与上述历史副本逐项比较，差异仅为默认 PIDs 64、来源说明和新增任务 5.1/5.2，没有增加限流或其他行为。

实施落点清楚：同步 `DockerSandBoxPoolConfig` 的 Spring 回退值及 `SandboxLimits.defaults()`；更新默认值单元断言、真实 Docker 默认断言，并使自定义编译/输出限额夹具使用当前默认 PIDs。显式配置覆盖继续有效，单元测试主动指定的合法 128 不应机械替换。本次收紧的是默认值，不是禁止显式设置更大的正数。

必需验收：

1. 重新运行相关 49 项单元/组件及 17 项真实 Docker 回归，核对测试输入确为当前 64 默认值；不沿用 128 的历史成功结果。
2. 在真实容器同时核对 HostConfig 的 PidsLimit、内核 pids.max=64，以及正常 javac/Java、普通退出和后续正常提交。PID 1、JVM 内部线程也计入该上限，正常编译运行不能只凭静态判断通过。
3. 两种线程创建程序都需观察 pids.current 不超过 64、pids.events 的 max 拒绝计数增加；确认超时旧容器已删除、补建后正常任务成功。仅 OOM 文案不足以证明 PIDs 限制生效。
4. 保留当前报告及失败证据，先汇总新证据再按归属清理本轮资源；既有报告不删除。若 64 下正常程序失败，应如实反馈，不放宽限额凑通过结果。

本次未改代码、未启动 Docker 或运行测试。只读 `openspec status --change sandbox-resource-hardening --json` 确认根目录 `/Users/lllyw/lyw-oj`、spec-driven 及必需产物齐备；`openspec validate sandbox-resource-hardening --strict --no-interactive` 在该目录退出 0，输出 `Change 'sandbox-resource-hardening' is valid`。结构通过不代替上述独立语义评审。

## 当前评审基线 SHA-256

| 文件（相对授权根） | SHA-256 |
|---|---|
| `openspec/changes/sandbox-resource-hardening/proposal.md` | `bad9e1e010c5afa21b12ba696f4173cb7d307c1e88a83d6d5d0bf87afd1908de` |
| `openspec/changes/sandbox-resource-hardening/design.md` | `54c541c75042b410f33717f69a993677bbd0ddb175518f7268cc4a3cbea023a4` |
| `openspec/changes/sandbox-resource-hardening/tasks.md` | `135d6765fa91dc24a038cb086bfdba0ab71e6f17815b8047bbcb1c18d0197ae1` |
| `openspec/changes/sandbox-resource-hardening/specs/judge/container-pool/spec.md` | `0516973a82803d9b0b231d9f2c1ee5d434b663d4316790fbbec4350b1a0f6bff` |
| `openspec/changes/sandbox-resource-hardening/.openspec.yaml` | `269801709915a5c0666ed7e7758feed8e250bdbc7e186b5fd146d05d93d64f40` |
| `.codex/plans/容器池PIDs64确认.md` | `e34543e2c90449a444a81138565a2d06d38ac669ac33e7364c7a7a343b1c8532` |

评审时间（UTC）：2026-11-10T09:11:54.553628+00:00。
