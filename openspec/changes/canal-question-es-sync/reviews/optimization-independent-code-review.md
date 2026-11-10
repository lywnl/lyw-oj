# Canal 题目同步优化独立代码评审

- 日期：2026-11-10（Asia/Shanghai）。
- 变更：`canal-question-es-sync`；风险等级：`strict`；核验方式：`machine`。
- 评审者：`/root/canal_review`，独立于本轮实现编写上下文。本评审者未修改生产代码、配置或测试，也未启动、停止服务；发现交给主线程修订后复核。
- 结论：**PASS（独立代码质量评审）**。本轮范围内未发现尚需修复的代码缺陷；本结论不代替主线程的完整验收、任务状态和隔离资源清理核验。

## 基线与范围

复用已通过的本轮优化规划及独立规划评审，核对任务 5.1～5.4。`check-plan --profile strict` 通过，记录中实施开始时间为 `2026-11-10T02:29:02.632551+00:00`。元数据沿用 `x-workflow-profile: strict`、`x-verification-mode: machine`，未改变等级或核验方式。

相对于 `.codex/canal-question-es-sync/optimization/before/`，本轮有 16 个修改文件和 2 个新增测试文件。评审覆盖这些差异及校验调用方、停止路径、PIT 清理、对账保护、维护入口和运行证据。已核对 SourcePositionGuard、TcpCanalSource、CanalEventMapper、JdbcQuestionReader、SyncProperties、prepare.py、entrypoint.sh、run_maven.py、开发 Compose 与优化前字节相同；没有把 ID-only 查询或 XXL-JOB 纳入实现。

当前相关代码、配置及测试的 60 文件清单见 [optimization-independent-code-files.json](/Users/lllyw/lyw-oj/openspec/changes/canal-question-es-sync/reviews/optimization-independent-code-files.json)，聚合 SHA-256 为 `de481b385c8e36ceb39171ef82a91cc35f04e451251ad266f966aed9b04379a7`。HEAD 为 `d274d57ffb21bc3b27eac1808d842cdbaa7922d4`，但工作区有未提交实现，评审依据包含实际文件内容，不能仅用 HEAD 复用结论。该清单用于界定独立评审版本，不替代证据助手的最终验收记录。

## 代码核对

| 范围 | 核对内容与结论 |
|---|---|
| ES 校验边界，任务 5.2 | [QuestionEsSyncRunner.java](/Users/lllyw/lyw-oj/oj-modules/oj-system/src/main/java/com/lyw/system/questionsync/QuestionEsSyncRunner.java:94) 在首次连接和重连时先校验，校验失败不打开 source；新一轮对账在 `reset/step` 前调用 `index.validate()`。后续批次和分页不重复校验，未加入永久有效的校验缓存。停止检查仍位于网络操作后和写入前。 |
| bulk 与确认 | [ElasticsearchIndexStore.java](/Users/lllyw/lyw-oj/oj-modules/oj-system/src/main/java/com/lyw/system/questionsync/ElasticsearchIndexStore.java:126) 直接组装完整 index/delete 请求，保留 NULL 输出及响应数量、操作身份、ID、索引名、状态和 error 检查；只有无 error 的删除 404 成功。索引缺失的 index/delete 404 均抛出阻塞异常。Runner 仍在全部写入成功后 ACK，写入失败尝试 rollback，ACK 不确定不推进本地进度。 |
| PIT 与自动修复 | [QuestionReconciler.java](/Users/lllyw/lyw-oj/oj-modules/oj-system/src/main/java/com/lyw/system/questionsync/QuestionReconciler.java:34) 的 reset 仍先 close PIT，再清理游标、候选及屏障；step 仅移除逐页 validate。源位点屏障、候选被新事件作废、超时放弃本轮、空库保护、查询失败不生成删除以及单线程写入均保留。 |
| 维护控制边界，任务 5.3 | [QuestionMaintenanceGate.java](/Users/lllyw/lyw-oj/oj-modules/oj-system/src/main/java/com/lyw/system/questionsync/QuestionMaintenanceGate.java:10) 每次读取 Environment，并在执行业务 Supplier 前拒绝新写入。删除本地锁及 awaitQuiescence，没有承诺它能排空其他进程。管理端 add/edit/delete 仍在 Gate 内执行。 |
| 独立维护入口 | [QuestionSyncMaintenanceRunner.java](/Users/lllyw/lyw-oj/oj-modules/oj-system/src/main/java/com/lyw/system/questionsync/QuestionSyncMaintenanceRunner.java:41) 不再依赖 Gate，但保留 repair 确认、增量关闭、只读 check、入口及显式创建后校验。维护上下文不导入 Gate，[maintain.sh](/Users/lllyw/lyw-oj/deploy/canal/maintain.sh:11) 仍检查确认参数，不再向维护 JVM 传入自己的题目门禁值。 |
| 正式目录，任务 5.1 | [生产 Compose](/Users/lllyw/lyw-oj/deploy/prod/docker-compose.yml:210) 的应用 state 与 Canal state 都使用 `${CANAL_STATE_DIR:-./canal/state}`，前者只读，后者可写；配置默认 `./canal/config` 且只读。`.gitignore` 覆盖正式 state、config、backups。环境变量覆盖和开发目录边界未改变。 |
| 迁移与回退 | [README](/Users/lllyw/lyw-oj/deploy/canal/README.md:110) 要求停止写入及消费者、完整复制 state/TSDB/配置、核对两处挂载并重新创建容器；回退使用已推进的最新完整 state，保留原目录。没有通过清空索引或重设 P 完成普通目录迁移。 |

减少元数据校验的行为与规划一致：运行中元数据变化在约定边界或实际操作报错时发现；关闭对账时没有周期映射检查。目标索引禁止自动创建的策略持续保留。上述边界没有被表述成逐批检查或跨进程自动排空。

## 发现及修订复核

### 首轮迁移演练的连接失败：已关闭

`canal-opt-it-01` 在迁移后的首次只读 check 返回 `ConnectException`，该轮退出 1，不能作为迁移、回退或维护排空通过的依据。评审读取原始记录后已即时反馈。旧 JVM 缓存容器名对应 IP 是可能原因，未取得缓存内容证明，不能把它写成已确证根因。

主线程将演练改为切换目录时退出旧探针 JVM，在新 Canal 就绪后启动新 JVM；回迁同样处理，并在 README 明确“挂载源变化需重新创建应用和 Canal 容器，restart 不更新绑定”。这一修订对齐目录迁移所要求的部署生命周期，没有修改生产 Java，也没有跳过 check、放宽连接失败或重设位点。旧失败记录与原脚本保留，新标签 `canal-opt-it-02` 从头执行并完成全部场景。该轮探针 2、3、4 的实际退出码均为 0。

### 证据版本差异：已限定适用范围

`canal-opt-unit-01` 的源码快照与当前相比，仅 `CanalIntegrationProbeTest.java` 有变化；该入口在单元轮次被跳过，因此该轮 63 项已执行单元结果仍可用于对应未变输入，不能据此证明当前探针已执行。`canal-opt-it-02` 的四个探针、业务真实回归和打包源码快照均与当前一致；运行时保存的 `integration-source.py` 与当前脚本一致，由这些证据覆盖更新后的真实入口。

无未解决阻塞项。历史独立报告未被覆盖，也未将原 Canal 实现的旧验收当成本轮通过证据。

## 实际测试证据

| 验证 | 本次核对结果 | 原始证据 |
|---|---|---|
| JDK 17 单元与业务回归 | `canal-opt-unit-01` 退出 0；JUnit 共 66 条记录，63 项执行通过，3 个真实入口因未启用对应开关跳过。包含连续 bulk 请求数量、各校验边界、两类缺索引 404、门禁、维护确认、对账屏障及停止路径。 | `.codex/canal-question-es-sync/evidence/canal-opt-unit-01-command.json`、同目录 sources、log 及 `canal-opt-unit-01/` JUnit XML。 |
| 配置与脚本 | `config-tests-01` 退出 0，9 项通过；实际 Compose 渲染确认默认目录、覆盖后共用 state、权限方向、开发目录和 Git 忽略，脚本参数检查确认维护 JVM 不设置在线门禁。 | `.codex/canal-question-es-sync/optimization/config-tests-01.json`、`config-tests-01.log`。 |
| 当前代码的真实集成 | `canal-opt-it-02` 退出 0，核对时 55 项场景记录均通过；使用 MySQL 8.0.40、Canal 1.1.8、ES/IK 8.5.3，覆盖无回查、失败重放、自动修复、DDL/TRUNCATE、缺失位点与索引、查询失败保护及本轮新增迁移/维护场景。 | `.codex/canal-question-es-sync/integration/canal-opt-it-02/run.json`、`evidence/integration-source.py`、`evidence/probe-events.jsonl` 及 command 日志。 |
| 实际进程退出 | ACK 前故障注入的探针 1 退出 137，属于明确注入的进程终止；探针 2、3、4、业务真实回归及打包均退出 0。打包使用 `-DskipTests`，只证明构建，不计作测试通过。 | `.codex/canal-question-es-sync/evidence/canal-opt-it-02-*-command.json` 与各自 sources/log。 |
| 规划和静态检查 | 本评审者只读执行 `check-plan --profile strict` 与 `git diff --check`，均退出 0；没有代替主线程登记验收或修改任务。 | 当前工具执行记录。 |

正常无积压样本通过 `_search` 观察，未强制 refresh：新增 795.1 毫秒、修改 942.2 毫秒、删除 934.8 毫秒。该轮 13 条带延迟记录的检查最大 1389.0 毫秒；这是本次隔离环境的样本结果，不外推为生产负载下的延迟分布。

## 高风险演练证据核对

**完整目录迁移与最新 state 回退。** `migration-state-manifests.json` 记录的完整 state 包含 `metadata-established`、`question-sync/h2.mv.db`、`question-sync/meta.dat`；配置包含生成的四个文件。独立重新计算并比较后，旧目录备份仍等于原清单，正式目录保留最新 state，回迁配置与原配置一致。源游标从 `mysql-bin.000004:5185` 推进至 `5730`，回退复制的是 `5730`；新旧 Canal 容器 ID 不同。恢复后题目 700 仍存在，索引 UUID `5L_NtR-vSNuYRKvgYHHcGA` 和映射保持，且新增后续修改可见。

**门禁、未提交事务和独立维护 JAR。** 当前探针调用真实 `QuestionServiceImpl.add/edit/delete` 并注入同一个 Gate，验证三个方法返回 3503；开关来源在探针中使用 `MockEnvironment`，不能据此宣称已验证生产 Nacos 推送或完整 HTTP 链路。外部写入使用真实 MySQL 客户端进程：连接 446 的题目 UPDATE 未提交时，`INNODB_TRX` 在开启门禁前后均为 1；业务存储仍显示已提交值。消费者确认 `STOPPED` 后，停止该写入进程，`docker inspect` 返回 false，事务数和 PROCESSLIST 连接数均为 0，题目值保持原提交值。对应 `command-0190.log`～`command-0198.log` 和探针事件均已核对。

只有上述排空检查完成后才打包并运行独立维护 JVM。`command-0202.log`、`command-0204.log` 分别输出 check/repair 的 `COMPLETE`，退出 0，保留索引且差异、修复数为 0；维护前后业务 Gate 仍开启，随后恢复唯一消费者并关闭 Gate，最后一次同步正常。该证据证明的是受控隔离演练，不把本地锁、固定 sleep、维护 JVM 自身配置或客户端超时当成跨进程排空依据。

## 交接边界

本评审者仅新增本报告及关联文件指纹。主线程继续完成任务状态、正式需求映射、隔离资源清理及机器验收登记；本报告不执行 `record-verify`、提交、推送、生产迁移或归档。生产仍需确认实际写入来源、门禁生效、事务结束及唯一消费者，沿用当前授权边界。
