# 独立规划评审

- 日期：2026-11-10（Asia/Shanghai）
- 风险等级：strict
- 结论：**PASS**
- 评审者：`/root/canal_review`。本执行上下文没有撰写上述规划、没有修改业务代码，也未启动服务或执行实现验收；仅独立读取规划、相关代码及固定版本官方源码，并维护本报告。
- 核验方式：machine。此处的 PASS 是规划语义结论；实施基线登记和指纹检查由主执行上下文在本报告完成后执行，不能将本报告当成测试或生产验证通过。

## 结论与覆盖

现有 7 项需求、12 个场景和 8 项任务覆盖了用户确认的单表、完整行不回查、无同步 MQ、新旧读写边界、保留索引维护、位点恢复和 5 秒真实搜索验收。用户已明确授权实施及隔离真实集成；生产账号操作、部署、提交、推送和归档没有被扩大授权。

| 检查面 | 结论 |
|---|---|
| 无回查完整行 | 普通处理器只依赖 source/transformer/writer；题目 Mapper 仅在维护、对账和已确认的业务读路径使用。完整覆盖、NULL、DATETIME、大 ID、主键变化和最后操作合并已有对应测试任务。 |
| ACK 与持久化 | 整批 ES 成功后确认；重放、确认不确定、持久卷容器重建均有验证要求。不宣称 ACK 等同位点立即落盘或 exactly-once。 |
| 唯一写入者 | B/C 双写和自动建索引均移除；增量与对账单通道；单活动实例及维护前关闭旧 writer 属于明确部署约束，没有偷换成自动选主。 |
| 保留索引 | check 只读，repair 需维护前提且不能默认删索引；旧映射保留，不从两端不同的 search_analyzer 重新强制覆盖现有设置。目标索引禁止隐式创建，显式维护才可创建缺失索引。 |
| DDL 与对账 | 对账页增加源位点屏障及变动 ID 候选失效，避免因消费积压越过尚未观察到的控制事件；BLOCKED 停止自动修复，空库保护作为额外防误清措施。 |
| 数据源与权限 | 生产业务库与数据源核对，复制连接与业务连接 server_uuid 一致；凭据注入且日志不输出题目正文/密码。生产只交付配置及操作说明。 |
| 验收与交接 | 明确真实 MySQL/Canal/ES+IK、搜索接口计时、无回查、部分失败、重启/重建、失效位点和基线差异验证；未以任务勾选或 mock 替代真实集成。 |

## 本轮已关闭的发现

### 1. 不能只靠 TCP 异常识别 parser 位点失效

初稿依赖 source 协议错误识别位点问题。Canal 1.1.8 的 parser 异常在独立线程记录和发送 alarm，而 TCP getWithoutAck 从 eventStore 取数，可能只得到空批，不能据此区分正常空闲和解析停滞。

设计已加入 PositionGuard：启动及定期检查持久化 meta.dat 与主库 SHOW BINARY LOGS，比较源 UUID，明确失效 BLOCKED；元文件正在写入而不完整时 RETRYING，不能回退显式 P。check 只检查 TCP 连通及只读元信息，禁止调用 subscribe/rollback/ack，防止检查改变活动消费者状态。此项关闭。

### 2. 对账不能在积压中的 TRUNCATE 之前改写索引

仅限制主库空时不清索引不足以处理“TRUNCATE 后立即重新插入”——主库重新非空，消费者还未看到 TRUNCATE，直接对账就可能删除旧文档，绕过人工维护边界。

设计现为：先形成候选页、读取源位点 P；消费确认追到 P 后才能修复，期间变动的候选 ID 作废；控制事件先 BLOCKED，无法证明到 P 就延后。任务 3.1 与设计包含相应故障交错验证。此项关闭。

### 3. 事务边界保留不等于全局位点总能立即到达

已核验 Canal 1.1.8：EntryEventSink 除 filterTransactionEntry 外还默认节流无目标行的空事务。设计现增加固定版本 Spring 配置，保持 filterEmtryTransactionEntry=true 并设 emptyTransctionThresold=0，让普通无关表事务提供结束进度；拼写与官方 setter 一致。

无关 DDL、ROTATE 等仍可能没有足以证明全局末尾的可消费事件，设计明确保守延后、记录候选等待年龄，超过 5 分钟告警并提供 check/维护 repair。此项在明确活性限制及测试要求后关闭，不得用空批、无位置的 HEARTBEAT 或本地当前时间伪造源进度。

## 实施与验收必须保留的边界

- 位点屏障必须使用同一源的可比较文件顺序/事件末尾位置；header.logfileOffset 是起点，eventLength 才能推导末尾。缺失位置的控制/心跳信息不提供追平证明。
- 真实测试需包含无关表事务、积压后 TRUNCATE 再插入、源位点丢失但 TCP 仍可连接/返回空批，以及元文件读取竞争；不能仅让 mock source 抛错替代真实恢复测试。
- 正常增量无题目查询的断言应区分控制面 SHOW/系统变量检查与业务表 SELECT；关闭对账后正常增量不得通过任何路径补读题目行。
- 30 分钟是对账调度周期，不是修复完成 SLA。无法证明源位点时保守延后属于本方案的显式边界，必须展示待定年龄与维护指引，不能静默悬挂。
- 当前评审通过不证明性能、版本运行兼容性或部署已验证；任务 4.1/4.2 的真实结果和最终独立代码评审仍是交付条件。

## 主要代码及官方核验来源

- 当前 B 端 QuestionServiceImpl 的 ES save/delete、C 端 refreshQuestion/saveAll 与 assembleJudgeSubmitDTO 的 ES 回填，以及双方 QuestionES 映射。
- [Canal 1.1.8 getWithoutAck 与 eventStore](https://github.com/alibaba/canal/blob/canal-1.1.8/server/src/main/java/com/alibaba/otter/canal/server/embedded/CanalServerWithEmbedded.java#L319)
- [parser 异常与 alarm](https://github.com/alibaba/canal/blob/canal-1.1.8/parse/src/main/java/com/alibaba/otter/canal/parse/inbound/AbstractEventParser.java#L292)
- [空事务节流与 setter](https://github.com/alibaba/canal/blob/canal-1.1.8/sink/src/main/java/com/alibaba/otter/canal/sink/entry/EntryEventSink.java#L122)
- [固定版本 Spring eventSink 配置](https://github.com/alibaba/canal/blob/canal-1.1.8/deployer/src/main/resources/spring/file-instance.xml#L53)
- [事件起点与长度](https://github.com/alibaba/canal/blob/canal-1.1.8/parse/src/main/java/com/alibaba/otter/canal/parse/inbound/mysql/dbsync/LogEventConvert.java#L915)

## 评审文件基线

以下哈希由本次读取的普通 UTF-8 文件直接计算；后续实质修改需重新核对受影响结论。

| 文件 | SHA-256 |
|---|---|
| `openspec/changes/canal-question-es-sync/.openspec.yaml` | `405b72295386e3dcd385195dec9a61c3579a088807a62148b3431d9a871f3b1b` |
| `openspec/changes/canal-question-es-sync/proposal.md` | `1036edb819ede8e947411d4dac1e80c3a9083ba7a1bcc5d3dbf39538a9e5f064` |
| `openspec/changes/canal-question-es-sync/design.md` | `4ad84dd25923dad172fbffb9698f0050ce3112a9987fcfbea76f8674776866b5` |
| `openspec/changes/canal-question-es-sync/tasks.md` | `fe37ec1a276f4782b2956729c5a928ac0397832b7eda8fc1e082b9ab5e7dc2fd` |
| `openspec/changes/canal-question-es-sync/specs/question/index-sync/spec.md` | `8e5d041f50df01935032620c80f54c4ab402ee0618d8e184b63b074a2a6d785e` |
| `.codex/plans/Canal完整行事件同步题目到ES执行计划.md` | `dbc0b70a4f0992b6873db1cbb2177828b24529a4352f5130dd41b33407f63948` |
