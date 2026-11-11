# 全局默认每秒 3 次：独立规划复审

- 结论：PASS（仅本次参数修订的规划）
- 变更：judge-submission-rate-limit
- 风险等级：strict；核验方式继续为 machine
- 评审者：/root/submission_rate_review
- 评审时间：2026-11-11T09:19:55.092746+08:00
- 独立性：本执行上下文未撰写本次参数修订规划、实现或测试；仅阅读必要内容并写入本报告。
- 最新需求来源：.codex/plans/redisson-global-three-20261111-091713-6359e1d3.md
- 复用基础：此前 independent-planning-review.md 和 independent-code-review.md 对未变更的身份、扣减顺序、HTTP 契约、Redis 接入、维护边界的结论。本次没有重新审计无关功能。

## 复审范围与结论

用户明确将同环境、所有用户/实例和普通/MQ 提交共享的全局默认值从每秒 2 次改为每秒 3 次。用户每 5 秒 1 次、空闲期限、错误码、尝试计数、客户端与容器池配置继续沿用原约定。proposal、design 和新增任务 5.1/5.2 已表达这一范围，spec 当前默认值及超限场景与之相符。

本次保留原 2 次版本计划、运行报告、并发数据和独立报告作为历史记录；新参数必须使用新运行证据验收。3 次/秒仍是试运行参数，不代表已测得的生产容量。本轮不操作业务 Redis、不停业务服务或部署；停服维护步骤仅在独占 Redis 内演练。

## 已处理发现

初读时，spec 的“总量额度耗尽”场景仍写“前两个通过、第三个拒绝”，与已改为三次的 Requirement 冲突。作者随后修正；本次已重新读取磁盘，确认 WHEN 为四个有用户额度的请求，THEN 为前三个通过、第四个拒绝。此问题已关闭，当前不存在阻塞项。

## 受影响验收安排

| 检查点 | 当前安排及判定 |
|---|---|
| Java 与配置文件默认值一致 | design 修订段和任务 5.1 明确同步修改 Java 默认值、bootstrap 的 global-permits 和维护说明，周期保持 1 秒 |
| 配置绑定测试仍有辨别力 | 最新来源明确要求使用不同于新默认 3 的合法覆盖值，属于任务 5.1 的配置/单元测试范围；实施时应改现有覆盖 3 的样例，例如使用 5 并断言得到 5 |
| 默认许可及超限边界 | spec 场景和任务 5.2 均要求前三次通过、第四次拒绝；请求应具备各自可用用户额度，并在额度尚未恢复的同一周期内检查 |
| 真实周期恢复 | 任务 5.2 明确保留 1 秒周期恢复的真实 Redis 验证；不能仅断言配置值 |
| 多客户端共享 | 新来源、design、任务 5.2 明确两个客户端合计共享三个许可；并发测试应断言合计 3 个通过，而非每客户端 3 个 |
| 存量 2 次规则 | design 明确新客户端参数为 3 时 trySetRate 不重置旧规则；任务 5.2 必须先证明未清理时仍按 2 次拒绝，再按既有受控维护流程删除后证明初始化为 3 |
| 其他行为回归 | 任务 5.2 包含现有单元/HTTP/提交查询回归；用户 5 秒限制及错误码没有随总量修改 |
| 证据与清理 | 新运行使用独占 Redis 和唯一临时目录，报告收口后清理本轮资源，核对无关改动和历史报告未改变 |

实现与测试中应区分“全局默认额度 2”的旧断言、“两个客户端”的结构数量，以及专门构造的“存量 2 次规则”样本，不能统一替换所有数字 2。

## 工作流核对

- x-workflow.workflow_profile 仍为 strict，沿用此前已说明的 CLI 元数据兼容方式。
- tasks 第 1～4 组保留历史完成记录，第 5 组两项任务未勾选；文本明确受影响的默认值结论以本组新证据为准，未把旧 2 次测试当成 3 次验收。
- 独立执行 openspec validate judge-submission-rate-limit --strict --no-interactive，退出码 0，输出 Change 'judge-submission-rate-limit' is valid。
- 当前代码/配置尚为旧 2 次版本，符合本次“编码前复审”的阶段；本报告没有宣称新参数已经实现或测试通过。
- 主执行者仍需绑定当前规划与最新来源、核验新实施入口，再完成参数修改、真实测试和独立增量代码复核。

## 基线

下列来源和规划构成本次复审基线；代码/测试/维护说明是编码前参考，允许在正式进入实施后按任务修改。旧独立报告保留不变。本次未修改任何业务代码、规划或旧报告，也未运行真实服务或分派子代理。

| 文件（相对项目根） | SHA-256 |
|---|---|
| `.codex/plans/redisson-global-three-20261111-091713-6359e1d3.md` | `0c1a0667010626dec6e708cd95c41f126cc1625f9b915037b4fb7f53658ae831` |
| `openspec/changes/judge-submission-rate-limit/.openspec.yaml` | `5edc39ea7b0ff2246f3e22f3d27fdf240c5f12b6066c0097ddad27b3653d1ee4` |
| `openspec/changes/judge-submission-rate-limit/proposal.md` | `d79d4c5446910ecbc85beb1b52665d01a7a9b7e00fdb73d57d67a2a2904925a0` |
| `openspec/changes/judge-submission-rate-limit/design.md` | `8bb97ab44f5570dba9656073c35fa48d4f658b3fe4af6542a42aaa8467630cf4` |
| `openspec/changes/judge-submission-rate-limit/tasks.md` | `66bf78a48cb80c78eafb5c6cd8a537235ea2dec384aca16e9d82974df143ec05` |
| `openspec/changes/judge-submission-rate-limit/specs/judge/submission-rate-limit/spec.md` | `8873b99539d088777f2dd2b692ed0ea897f069602c4cb581a60bc7a373ce343d` |
| `openspec/changes/judge-submission-rate-limit/reviews/independent-planning-review.md` | `83529a36337f2c29a3468cd698835c0392b500f36877c1f8d9a879fb0a5d1bd6` |
| `openspec/changes/judge-submission-rate-limit/reviews/independent-code-review.md` | `b72488e1c28e96e08e137b1051cf6076f630389e80a0b6656b1234df3ae1e99b` |
| `oj-modules/oj-friend/src/main/java/com/lyw/friend/config/SubmissionRateLimitProperties.java` | `e0199c8f882ab176ad30db67e183873de0a8e0ede25abc3fc1fdb3a908f8fa60` |
| `oj-modules/oj-friend/src/main/resources/bootstrap.yml` | `2fe0174a2ef26cf463d23d01e41acd4b09c94f683d8d148a591b384a60411db1` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/config/SubmissionRateLimitConfigTest.java` | `84dc5536ef35693200aecf5f3146fe5678283af22acf26f110ec0d550106948b` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/service/SubmissionRateLimiterTest.java` | `98f24f0e7e1e74a444971bce343d9bb4c293179dc09276ad40f1fd9b4889d65d` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/service/SubmissionRateLimiterRedisIntegrationTest.java` | `66b34ebba728fbf60090dc01b138598ffbb82fc2d29ae9c1cc265e7801729067` |
| `oj-modules/oj-friend/docs/submission-rate-limit.md` | `e8a8e60c5026ab3e0ecaaef46f359b43d62212efa5ac869850f24669851eaaaf` |

