# Canal 题目同步优化实现验收

- 日期：2026-11-10（Asia/Shanghai）。
- 变更：canal-question-es-sync；风险等级 strict；核验方式 machine。
- 结论：**Ready for Archive**。本轮仅完成实现与验收，不执行归档、提交、推送或生产部署。
- 依据：本轮优化规划 PASS、2026-11-10T02:29:02.632551+00:00 的真实实施入口记录、任务 5.1～5.4，以及当前源码与实际测试。规划文件的阶段提示按编写时保留，当前交付状态以任务勾选及本报告为准。
- 原实现的评审、验收及指纹逐字保留于 reviews/history/pre-optimization、reports/history/pre-optimization，原始 it13 等证据未覆盖。本轮通过不依赖旧 Ready for Archive。

## 实现结果

1. 生产 state/config 默认改为 deploy/prod/canal/state、deploy/prod/canal/config；Canal 与应用挂载同一 state，应用只读，配置只读，生成目录忽略 Git。README 补齐生成、排空、迁移与携最新状态回退流程，并明确挂载源变化必须重新创建两端容器。
2. ES 元数据校验集中到首次连接、重连、新一轮自动对账、维护入口和显式创建后。稳态批次与后续分页不重复三项元数据请求；完整覆盖、逐项响应核验及整批 ACK 仍保留。
3. 删除独立维护 JVM 的本地排空锁、Gate 依赖和其自身的题目门禁参数。在线实例仍逐次执行 3503 门禁，repair 保留明确确认；实际排空通过已识别的请求/进程及 MySQL 会话、事务核验。

自动修复及源位点屏障、候选变更作废、PIT 清理、空库保护、DDL/TRUNCATE 阻塞、SourcePositionGuard、P+T 和连接超时均保留。反向对账只查询 ID、XXL-JOB、新服务、同步 MQ、版本列与自动选主未纳入。

详细需求、场景、任务与实现/证据的映射在 reports/verification-manifest.json，共 35 条（7 项需求、16 个场景、12 项任务）。本报告不重复列第二份完整映射。

## 实际验证结果

| 验证 | 结果与原始证据 |
|---|---|
| JDK17 单元及业务回归 | canal-opt-unit-01 退出 0；66 条 JUnit 记录中 63 项执行通过、3 个真实入口按条件跳过。原始日志、命令、源码快照和 JUnit 位于 .codex/canal-question-es-sync/evidence/。 |
| 配置与脚本 | optimization/config-tests-01 退出 0，9 项通过；包含两套实际 Compose 渲染、生产覆盖后同源挂载、权限方向、Git 忽略和维护确认参数。 |
| 完整真实链路 | integration/canal-opt-it-02/run.json 退出 0，55 项场景记录通过；其后另有 1 项资源清理记录。使用隔离 MySQL 8.0.40、Canal 1.1.8、ES/IK 8.5.3，没有连接生产数据。 |
| 真实业务回归 | canal-opt-it-02-friend-real 共 14 项通过、0 跳过，包括真实 MySQL 取题/判题装配及既有提交回执、结果查询回归；执行时真实 ES 已停止。 |
| 搜索可见性 | 新增 795.1 ms、修改并清 NULL 942.2 ms、删除 934.8 ms。13 条延迟记录最大 1389.0 ms，均通过 _search，未强制 refresh。 |
| 位点及目录迁移 | 完整 state 含 meta.dat、h2.mv.db、metadata-established；文件内容与模式核对，迁移后重建 Canal 与消费 JVM 续传。游标从 mysql-bin.000004:5185 推进至 5730，回迁携带 5730，索引 UUID、映射和历史题目 700 保留。 |
| 真实维护排空 | 门禁开启后实际 QuestionServiceImpl 三个写方法返回 3503；外部 MySQL 写入连接 446 的未提交事务仍存在。消费者 STOPPED，写进程退出后会话与事务均为 0、未提交修改回滚，随后独立维护 JAR 的 check/repair 均 COMPLETE、退出 0，恢复后同步正常。 |
| 故障与保护 | 无题目回查、部分写/ACK 不确定重放、真实响应读取超时、ACK 前进程终止、自动对账修复、DDL/TRUNCATE、MINIMAL/STATEMENT、失效/丢失位点、索引丢失及查询失败保护均验证。索引丢失分别包含 index 与 delete，普通重复删除成功。 |
| 资源与静态检查 | 本轮任务容器、网络均为 0，见 optimization/final-cleanup.json。shell/Python 语法、git diff --check、规划结构与有效基线检查通过。 |

## 高风险证据与版本核对

目录迁移、回退和维护排空的具体记录见 integration/canal-opt-it-02/evidence/migration-state-manifests.json、probe-events.jsonl 及 command 日志。Docker 写入进程退出和 MySQL INNODB_TRX/PROCESSLIST 的观测发生在独立维护 JVM 启动之前；3503 或客户端超时未被当作排空证明。

业务门禁探针使用真实 Service 和 Gate，开关由测试 MockEnvironment 控制；这不代表已验证生产 Nacos 推送或完整 HTTP 入口。外部写事务、数据库观察、容器停止及维护 JAR 均真实执行，未用模拟存储替代。

unit-01 的源码快照与当前仅 CanalIntegrationProbeTest.java 不同，该入口在该轮未启用；63 项实际执行测试的相关输入未变。当前探针由 it-02 的四个真实探针执行覆盖；探针、friend-real 及打包的应用输入快照均与当前一致，保存的 integration-source.py 也与当前脚本一致。打包 -DskipTests 只计构建，不计测试。

ACK 前终止的探针 1 退出 137 是计划内故障注入，由后续进程的重放和最终状态验证；探针 2、3、4、业务回归和打包均退出 0。未将任何 skip 或计划内被终止的进程记作普通测试成功。

独立代码评审为 PASS，见 reviews/optimization-independent-code-review.md；评审者 /root/canal_review 未编写实现、配置或测试。60 个相关文件的独立版本清单见 optimization-independent-code-files.json，无未解决阻塞项。

## 保留的失败与修正

canal-opt-it-01 在目录迁移后的首次 check 出现 ConnectException，整体退出 1，未通过本轮集成验收。失败记录及当时的 integration-source.py 保留，未改写为成功。

该演练原先重建 Canal 后复用旧消费 JVM，与挂载源变化时需要重新创建应用容器的实际流程不一致。演练已改为迁移、回迁时退出旧 JVM，在新 Canal 就绪后启动新 JVM，并补正文档。旧 JVM DNS 缓存是可能原因，未直接取得缓存证据，因此不将其写为已确证根因。未通过跳过 check、重置位点或放宽失败处理解决问题；it-02 使用新标签从头重跑，全部场景通过。

## 交付边界

同步配置默认关闭；单活动消费者仍由部署保证。正常差异自动修复，危险事件按既有维护流程处理，不能把 30 分钟调度间隔当作无条件修复时限。校验频率减少后，元数据异常在约定边界或请求失败时发现，目标索引禁止隐式创建持续保留。

本轮实际迁移和事务演练只使用 .codex 内隔离数据。生产的业务库、凭据、写入来源、门禁生效、事务结束、挂载和网络尚未接入验证；生产账号、迁移、启停及上线仍需对应环境授权。代码没有提交或推送。
