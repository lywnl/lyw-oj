# Canal 题目同步优化独立规划评审

- 日期：2026-11-10（Asia/Shanghai）。
- 变更：`canal-question-es-sync`。
- 风险等级：`strict`；核验方式：`machine`。现有 `.openspec.yaml` 使用已核对的 CLI 扩展字段 `x-workflow-profile`、`x-verification-mode` 表达这两项约定，未降低等级或改变核验方式。
- 结论：**PASS（仅规划）**。
- 评审者与独立性：`/root/canal_review`，独立于主规划编写上下文。本评审者未撰写本轮 proposal、specs、design、tasks 或业务实现；提出的规划修正由主线程落实，再由本评审者复核。
- 范围：本轮三项优化和任务 5.1～5.4，以及受其影响的既有一致性保护。1.1～4.2 的完成状态和旧评审、旧验收记录仅为历史背景，不构成本轮通过依据。

## 评审范围与来源

依据用户已确认的 Canal 完整行同步方案、本轮保留自动修复的优化范围、当前规划及相关源码评审。未引入新的外部需求文档。采用项目 `AGENTS.md`、`openspec-review-change/SKILL.md`、共享工作流与评审检查表；评审时项目根为 `/Users/lllyw/lyw-oj`，HEAD 为 `d274d57ffb21bc3b27eac1808d842cdbaa7922d4`。工作区存在尚未提交的原实现，不能仅用 HEAD 代表本次输入。

| 规划范围 | 源码核对与评审结论 | 任务及验收承接 |
|---|---|---|
| 生产持久化目录 | `deploy/prod/docker-compose.yml` 的默认路径改为 `./canal/state`、`./canal/config`，相对于生产 Compose 文件目录解析；Canal 可写、应用只读且共用同一 state。生成配置、state 和备份须忽略 Git。迁移完整 state 与配置，不能把改默认值当作已迁移。 | 5.1；“生产持久化目录迁移”；5.4 真实目录迁移、容器重建续传及回退。 |
| ES 校验集中在边界 | `ElasticsearchIndexStore.write()` 去掉逐批校验，`QuestionReconciler.step()` 去掉逐页校验；首次连接、重连、新一轮对账、维护入口、显式创建后仍校验。bulk 条目数量、身份、状态及错误检查保持。 | 5.2；“ES 校验集中于生命周期边界”“首次校验后索引被删除”；5.4 自动修复与异常重放回归。 |
| 删除无效维护锁 | 独立维护 JVM 的 Gate 锁不能约束在线实例。删除其调用、依赖、维护上下文导入及脚本中的本进程门禁参数；在线业务继续逐次检查开关并返回 3503，repair 保留明确确认及单写者前提。 | 5.3；“题目维护开关”“独立维护进程的控制边界”；5.4 未提交真实写事务及进程退出演练。 |

5.1～5.3 可在有效实施基线下推进，5.4 依赖三项实现完成。反向对账仅查询 ID 保留为候选，不作为本轮实现或交付前置条件；XXL-JOB、题目 MQ、Outbox、自动选主及新常驻服务均未纳入。

## 发现与处置

### 维护排空缺乏可执行证明：已关闭

原规划仅要求确认在途结束，未明确当前没有在线 drain 接口时如何操作。源码中的本地读写锁及其测试只能证明单 JVM 互斥，独立维护进程调用 `awaitQuiescence()` 无法证明在线请求已经结束；仅靠门禁配置或固定等待时间仍有修复和旧写入并发的风险。

修订后的 [design.md](/Users/lllyw/lyw-oj/openspec/changes/canal-question-es-sync/design.md:49) 已给出两条证据路径：跟踪受控请求/导入终态，并事先识别连接及事务，通过 `information_schema.PROCESSLIST`、`INNODB_TRX` 核对；无法可靠观测时，在获准短维护窗口停止相关写题应用、导入进程及消费者，核对进程退出和数据库连接/事务结束或回滚。来源无法识别时停止维护，不扩大为终止整个数据库。相应真实演练已写入设计、计划入口和任务 5.4。

实施核验时，终态须代表执行者及事务完成，客户端超时不能单独作为完成证明；查询权限须能看见事先识别的写入来源。所需可见性由具体环境授权覆盖，不扩大 Canal 账号权限。本轮不要求新增在线管理接口。

### reset 描述可能误删 PIT 清理：已关闭

原“只重置游标和候选，不隐藏网络请求”的描述与既有 `reset → closeCursor → DELETE /_pit` 不一致，可能使实现误删资源释放。修订后的 [design.md](/Users/lllyw/lyw-oj/openspec/changes/canal-question-es-sync/design.md:27) 明确仅禁止将 validate 隐藏进 reset，并保留 PIT 关闭、游标和候选重置。此处无需改变对账协议。

## 风险边界与判断

- 元数据检测频率的变化已明确接受并写入规划：运行时映射或策略变化在约定边界或实际请求报错时发现；关闭对账后不承诺周期映射检查。目标索引禁止隐式创建的策略必须持续生效，不能以放开自动建索引弥补校验频率下降。
- 已核对当前写入路径：增量由 Runner 调用 writer，自动及维护修复由 Reconciler 调用 writer，维护入口和显式 create 已有校验落点。迁移校验时不得遗漏这些边界或在校验失败后继续相关工作。
- 当前 bulk 判断只把无 `error` 的文档删除 404 作为成功，带错误的索引缺失 404 会阻塞。只读核对 Elasticsearch v8.5.3 官方 [TransportBulkAction.java](https://raw.githubusercontent.com/elastic/elasticsearch/v8.5.3/server/src/main/java/org/elasticsearch/action/bulk/TransportBulkAction.java) 后，普通 DELETE 不触发索引自动创建、缺失索引通过条目 Failure 返回错误的行为与设计相符。该源码核对不能替代针对本项目实际响应的测试。
- 对账保持单线程写入、源位点屏障、候选变更作废、空库保护及查询失败不删除。SourcePositionGuard、P 加源时间戳、空事务配置和可取消连接超时继续作为保留行为，不能因本轮减少请求或删锁而移除。
- 目录迁移须复制原有效 state、TSDB、metadata-established 和配置，固定身份且复用原位点。新目录已推进时，回退使用最新有效完整 state，不能直接恢复切换前陈旧副本；位点失效或状态不明转入既有维护恢复。
- 无未解决阻塞项，无需另行决定的实质性警告。以上取舍和实施检查已在本轮设计与任务中承接。

## 必需验证与实施交接

1. HTTP 记录证明边界校验后连续 bulk、后续分页不重复发 HEAD 索引、GET mapping、GET cluster settings；首次连接、重连、新一轮对账、维护及显式创建仍校验，失败后不进入对应读写阶段。
2. 首次校验后删除索引，分别验证 index、delete 操作的条目错误导致阻塞或保留未确认批次；正常索引中删除不存在文档仍成功。保留部分失败、ACK 不确定、完整 NULL 覆盖、重放及自动修复测试。
3. 动态在线门禁继续阻止新增、修改、删除，返回原 3503；check 只读、未确认 repair 拒绝、维护入口正常结束。真实保留一笔未提交题目写事务，证明 3503 只拒绝新请求；核实旧写完成或回滚、相关进程与原消费者退出后，再运行独立维护 JAR。
4. 两套 Compose 渲染与环境变量覆盖检查同一 state 及权限方向；隔离真实环境完整迁移、重建续传及最新 state 回迁，保留索引 UUID、映射和历史文档。正式默认目录的改动不授权真实生产迁移。
5. 优化后的当前代码运行 JDK 17 单元及业务回归，使用真实 MySQL 8.0、Canal 1.1.8、ES/IK 8.5.3 完成受影响集成验证；通过 `_search` 验证无积压时增删改 5 秒可见，不强制 refresh。日志、命令、退出码、源码状态和延迟记录用新标签保存，不覆盖旧证据。

本轮独立评审未启动服务、未运行业务测试，未执行生产操作。本评审者只新增本报告；聚合 `change-review.md`、机器 `record-plan/check-plan/start-apply` 及后续实现由主线程负责。规划 PASS 不代表优化已实现、测试已通过，也不替代生产启停、迁移、提交、推送或归档授权。

## 规划版本核对

在本报告落盘前重新读取并计算以下文件 SHA-256，均与本轮语义评审完成时一致。此表记录独立评审所针对的版本；正式机器基线仍由主线程的证据助手登记，不能用此表替代门禁。

| 文件（相对项目根） | SHA-256 |
|---|---|
| `.codex/plans/Canal题目同步优化计划.md` | `0b633bb9ea7e37b807869fa116bbd07cef55d5f632c1eeb300b2bb28f71b3f67` |
| `openspec/changes/canal-question-es-sync/.openspec.yaml` | `405b72295386e3dcd385195dec9a61c3579a088807a62148b3431d9a871f3b1b` |
| `openspec/changes/canal-question-es-sync/proposal.md` | `454cab06eb935c84c3eb966cdd90f4d3b3b695f722f7d93662d499040952d852` |
| `openspec/changes/canal-question-es-sync/specs/question/index-sync/spec.md` | `7c819aaa14d489d1bb3782a72d0ad22a44a6d00a97eb992a94f8281447bbecf2` |
| `openspec/changes/canal-question-es-sync/design.md` | `42073630b706ac522aae7b7a8d8dea8bb3cd58339db3508a7fc6b91e1e1afbb7` |
| `openspec/changes/canal-question-es-sync/tasks.md` | `241e524c58c74f5f2233a128f0ab0436351ac5141e999913cb34d7d8a2fbd341` |
| `AGENTS.md` | `20357a5428a4639631d2cccf23c6b9eb47b615960acc1f30db1a77c7f4bb4409` |
| `.agents/skills/openspec-review-change/SKILL.md` | `5274cb3aeb7db1ff741925013940778134bc493aca77ed25b9b1c7c2d3aeba76` |
| `.agents/shared/openspec/workflow-policy.md` | `8a324454d91d27dda2fbf140198cbbc7413c3ca3fd9a1af75553b12517b60d1d` |
| `.agents/shared/openspec/review-checklist.md` | `f9b0b88624f17a916b52c027ec9490abc64484ad935b33dc24df36a33a76a3fa` |

此前已执行的 `openspec status --change canal-question-es-sync --json`、`openspec instructions apply --change canal-question-es-sync --json`、`openspec validate canal-question-es-sync --strict --no-interactive` 均退出 0；必需 proposal/specs/design/tasks 齐备，任务为 8 项历史完成和 4 项优化待办。结构校验仅为预检证据，本报告 PASS 来自上述独立语义评审及修订复核。
