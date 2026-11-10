# 实现设计

## Context

用户已确认并授权 `.codex/plans/Canal完整行事件同步题目到ES执行计划.md`。现有系统使用 Java 17、Spring Boot 3.0.1、MyBatis-Plus、Spring Data ES；主机 JDK 25，真实构建使用隔离 Docker JDK 17。Docker 为 arm64，8 GiB 内存；Canal v1.1.8 有 arm64 镜像，ES/IK 8.5.3 资源存在。

2026-11-10 优化修订承接用户“按照这个写一份优化计划”：保留自动修复，落实下文三项优化。当前仅完成规划修订，代码仍为原实现。元数据沿用当前 CLI 的扩展字段 `x-workflow-profile: strict` 与 `x-verification-mode: machine`，分别表达项目的 strict、machine 语义，不更改风险等级或核验方式。原评审和验收记录保留为历史，需对修订后的基线重新评审及验证。

## Goals / Non-Goals

按 question/index-sync 的七项需求实现单表最终一致。保持旧索引映射、字段命名、公共请求和结果查询；不改判题队列/容器池、不增加数据库表或源版本，不承诺故障重放中单调可见。

## Decisions

### 组件与边界

在 oj-system 的独立 questionsync 包提供：配置、纯行转换器、Canal source 适配、ES writer、主库维护 reader、分步 reconciler、状态与 runner。普通增量处理器只依赖 source/transformer/writer，不依赖题目 Mapper。维护 reader 仅供启动配置核验、check、repair、对账使用。

使用 canal.client 1.1.8；保留固定 destination=question-sync 与稳定 clientId。配置 enabled 默认 false，batch=100，idle=200ms，timeout=5s，退避 1/2/4/8/16/30s，对账间隔 30min、page=500。单活动实例由部署显式保证，不实现自动选主；同一进程所有写入共用执行通道，maintenance 与增量模式互斥。

### 行转换与写入

过滤库表，区分事务边界与目标表 DDL；针对 raw/decoded 两种 Message 提取 RowChange。字段采用现有 QuestionES 的十项投影（含 createTime）；afterColumns 中不按 updated 标记过滤。NULL 与缺字段不同，数据类型和主键必须可解析，DATETIME 保留本地原值。可忽略未投影的额外列；主键变化先删除旧键再写新键，同批按源顺序合并。

ES 使用已有底层 RestClient，以 UTF-8 NDJSON 执行 bulk index/delete；index 完整替换，不用局部 update。逐项校验响应，只有文档不存在且无操作错误的 delete 404 成功；索引缺失、映射/权限等确定性错误 BLOCKED，其余瞬时故障重试。保留现有映射，只核对字段类型与日期契约，不强制重写两端不同的 search_analyzer。两端 @Document(createIndex=false)。生产/隔离 ES 的 action.auto_create_index 针对 idx_question 禁止隐式创建（其他索引照旧）；显式维护 PUT 创建不受该限制。

ES 元数据校验集中在首次连接、每次重连、新一轮自动对账开始、check/repair 入口；显式创建索引后仍核对新索引契约。QuestionEsSyncRunner 在建立连接时及将 reconciling 从 false 转为 true 前调用 index.validate，维护入口沿用现有校验。ElasticsearchIndexStore.write 不再调用 validate，QuestionReconciler.step 不再按页调用 validate；不把 validate 隐藏进 reset，保留 reset 既有的 PIT 关闭、游标及候选重置。每次 bulk 的响应身份、条目数量、逐项状态、NULL 完整覆盖及整批 ACK 规则继续生效。对账中途失败后放弃当前轮，重连及新一轮仍重新校验，不增加永久有效的校验缓存。

本轮减少三个元数据请求（HEAD 索引、GET mapping、GET cluster settings）在每批/每页上的重复，不承诺省略 bulk 校验。运行时元数据变更的检测时点调整为上述边界或实际请求报错，不能宣称仍逐批检查映射；对账关闭时不会凭空产生周期映射检查。部署中的目标索引禁建策略持续生效，索引或集群策略变更须进入既有维护流程，不能同时放开隐式创建来掩盖索引缺失。

### 批次状态与恢复

状态 DISABLED/STARTING/RUNNING/RETRYING/BLOCKED/STOPPED，记录最近确认、重试、错误类型和差异计数，不输出事件正文/凭据。空批不 ACK；有效无业务行批次可 ACK。整批写成功才 ACK；ACK 异常重连恢复未确认批次。消费永久错误和已知 binlog 缺失错误锁定 BLOCKED，同时禁止自动对账，不能通过重连默认位点绕过；重启也会在原未确认事件再次阻塞。Canal server 必须显式配置有效起点或使用已有持久化元数据，禁止缺省当前位置启动作为恢复手段。

Canal source、ES、维护查询设置有界超时。source 协议错误需区分可重连故障与位置错误；连接时读取 Canal 原始错误保留类型但脱敏输出。收到控制性 DDL/TRUNCATE 后立即阻塞整批，不能先写同批普通操作。

Canal parser 的错误未必通过 TCP 发送给消费者，因此不能只靠 getWithoutAck 抛错判断失效位点。启动和每 30 秒由只读 PositionGuard 读取共享持久卷中的 meta.dat（固定 destination/clientId 的 cursor；首次无元数据时必须提供显式起点 P），使用专用复制账号执行 SHOW BINARY LOGS，验证源文件仍存在、偏移在文件范围内；启动同时比较复制源与业务数据源 server_uuid，拒绝同名异源数据库。应用只读挂载 Canal state，普通消费不查询题目行；元信息暂不可用时进入 RETRYING 并暂停自动对账，明确失效时 BLOCKED。已经存在的元数据不完整时只能重试，绝不回退显式 P 或按首次启动处理。check 使用相同只读检查及 TCP 端口连通，不调用 Canal subscribe/rollback/ack，避免检查改变活跃消费者状态。

### 业务调整与维护门禁

B 端去除所有 ES 调用，Redis 逻辑沿用。C 端列表仍读 ES、去掉 count/自动全量；详情和组装判题数据读 MySQL，API 空值/不存在行为及校验沿用。维护错误码 3503 及消息不变；QuestionMaintenanceGate 逐次读取在线实例的 Environment，只守住 B 端 add/edit/delete，不影响其他管理接口。

check/repair 由同一 oj-system 包的一次性、非 Web 维护入口和项目脚本启动，不注册新的常驻服务或远程接口。check 只读，检查数据源库、binlog ROW/FULL、配置起点/元数据、ES 映射及双向差异。repair 要求操作方明确确认在线实例门禁已生效、所有题目写入及原索引写入者已暂停，并要求维护进程自身同步 enabled=false；支持索引不存在时显式创建，已有索引只定点写删。不自动重设 Canal 位点。

删除 QuestionSyncMaintenanceRunner 的 gate.awaitQuiescence 调用及其 Gate 依赖，从 QuestionMaintenanceGate 去掉 ReentrantReadWriteLock 和 awaitQuiescence，保留 write(Supplier) 的开关检查与业务调用。维护专用上下文不再导入 Gate；maintain.sh 保留 check/repair、--confirm-maintenance 和 canal.sync.maintenance-confirmed，不再向维护 JVM 传入只会影响它自己的 question.maintenance.enabled。维护进程的 enabled=false 同样不证明在线消费者已退出。

维护操作顺序为：实际在线实例启用题目门禁、暂停外部 SQL/导入 → 等待并核实在途写入结束 → 停止原同步器并确认退出完成 → 确认后执行 repair → 恢复唯一同步器并检查 → 恢复题目写入。不能用维护 JVM 的锁、固定 sleep 或配置值代替排空证据；无法证明时不执行 repair。沿用现有停流/发布手段，不新增分布式锁、远程管理接口或长期停整个管理端。普通定期自动修复仍使用消费线程和屏障，不要求开启维护门禁。

排空采用可执行的证据路径：能跟踪受控请求/导入时，确认其成功或失败终态，并通过 MySQL information_schema.PROCESSLIST、information_schema.INNODB_TRX 核对已识别连接与事务无剩余题目写入；事先记录连接/事务 ID，不能仅凭查询当时未见 SQL 推断排空。当前应用没有在线在途计数或排空接口，因此无法可靠识别请求时，使用获准短维护窗口的进程停止路径：先禁用所有题目写入来源，停止相关写题应用/导入进程（包括承载消费者的指定实例），以 docker inspect 的 State.Running=false 或相应进程管理器退出状态确认退出，再核对这些数据库连接/事务已结束或回滚。共享账号或网络使来源无法确认时，不能扩大为终止整个数据库或宣称排空，应先明确来源。操作核对所需数据库权限由相应环境授权覆盖；本次仅在隔离环境执行。

### 对账与安全删除

按主键游标分页读 MySQL，通过 ES mget 比较投影；反向使用 ES 分页 ID 与 MySQL 批量存在性查询找多余项。所有读取和页面解析完成后再形成删除集合，查询/分页失败不执行该页删除。不在 BLOCKED 中对账；自动对账发现主库空而 ES 非空只报告需人工核对，不自动执行全索引清空，防止绕过 TRUNCATE 人工维护；显式 repair 可在维护窗口核对后清理多余文档。读到旧快照/并发变化可能短暂回退，由随后增量/下轮对账收敛。

每次循环优先处理一个增量批次，再准备至多一页对账候选。候选完成读取后记录源当前位点 P，必须确认消费进度到达 P 才可修复；追平过程中收到候选 ID 的变更就放弃该 ID 本页候选。消费进度采用保留的事务边界及事件 header 的文件/offset+eventLength，不能用空批假定追平；无法证明则延后修复。该屏障阻止积压中的 TRUNCATE/DDL 被对账绕过，发现控制事件先进入 BLOCKED。同线程顺序写，故障时不另起补偿线程。周期和页数可配置，check 完整遍历但不写入，显式 repair 在已停写维护窗口内执行。

Canal 1.1.8 默认还会节流无目标行的空事务；在固定版本 Spring 配置中保持 EntryEventSink.filterEmtryTransactionEntry=true，并设置 emptyTransctionThresold=0（沿用源码拼写），提供每个空事务结束位置而不扩大行数据订阅。无关 DDL/ROTATE 仍可能没有可消费末尾位点；因此记录候选页等待时间，超过5分钟告警并指向 check/维护 repair，不把30分钟扫描调度表述为修复完成时限。真实测试覆盖无关表事务推进，以及积压中 TRUNCATE 后重新插入不能被对账绕过。

### 正式生产目录

生产 Compose 中 CANAL_STATE_DIR 的默认值调整为 ./canal/state，CANAL_CONFIG_DIR 调整为 ./canal/config，按 deploy/prod/docker-compose.yml 所在目录解析，即 deploy/prod/canal/state 与 deploy/prod/canal/config。Canal 读写 state，oj-system 只读挂载同一 state 到 /canal-state；配置仍只读挂载。保留环境变量覆盖、默认关闭和生产端口边界。开发及测试的 .codex 临时目录继续沿用。

在 .gitignore 中明确忽略正式目录下生成的 state、配置和部署备份，README 区分开发示例与生产生成位置。prepare.py 仍只生成配置，保持显式 P、源时间戳、文件权限和凭据注入，不在源码或报告写入账号明文。修改默认值不能被视为已经迁移；已有部署先核对实际挂载及目录内容，再按下文维护迁移步骤执行。

### 候选小优化

反向对账当前调用 JdbcQuestionReader.find 读取完整投影，调用方只使用 containsKey 判断存在性。可在后续明确纳入时改为仅 SELECT question_id 并返回 ID 集合，正向内容比较保留完整行；同时验证空列表、大整数 ID、读取失败不误删及空库保护。本轮只登记候选，不增加必需 checkbox、不改接口，也不以该项未实施阻止三项主优化的交付。

## Risks / Trade-offs

- 发布双写窗口 → 两端旧进程退出且消费关闭后建立基线；仅一个活动消费者。
- 跨存储无事务、重放短暂回退 → 有限超时、保留确认边界、幂等完整覆盖、周期对账；不宣称 exactly-once 或版本单调。
- binlog 清理/DDL → 阻塞，不静默跳过；维护建立新基线和有效 P 后恢复。
- 暂无生产连接验证 → 仅生成配置/脚本；真实集成在独立网络/数据库/索引执行。
- ES 不可用 → 不因 Repository 自动建索引阻止与搜索无关业务启动；同步异步重连或阻塞，题目数据读 MySQL。
- 优化后的校验频率 → 元数据故障在约定边界或操作失败时识别，持续保留目标索引禁建和逐项 bulk 校验，不将减少请求描述为取消检查。
- 独立维护进程无法跨进程排空 → 移除无效锁并明确操作方确认边界，实际修复前核实在线门禁、在途写入和消费者退出。

## Migration Plan

先检查运行值和持久化条件；无需因同值固化重启真实 MySQL。维护窗口暂停题目写入及外部导入并等待在途完成，发布去双写版本、消费关闭，确认旧 ES 写入者退出。记录 P，check 后按差异 repair，配置首次 Canal 起点或复用既有有效元数据，启用单消费者，再恢复写入并测 _search。

回退须先停止消费者与对账，再恢复旧应用，不能两套 writer 同时运行。保留索引、元数据和原配置；生产执行另核授权。位点失效恢复须先备份元数据/记录当前状态，维护核对并配置新 P；脚本不默认删除 meta.dat 或修改历史。

本轮目录迁移在核对目标环境授权后执行：暂停题目及结构写入，确认在途写入完成，停止消费者和 Canal Server；记录并完整备份原 state、配置、身份及位点。将 meta.dat、TSDB、metadata-established 和配置完整复制到正式目录，核对权限、内容及两处 state 挂载，保留旧目录。先启动 Canal Server、执行只读 check，再启动唯一消费者，验证续传和搜索后恢复写入。普通目录迁移复用原有效位点，不重生成新的 P，不自动 repair 或清空索引。位点已失效时转入原有基线恢复流程。

回退本轮优化时回到原 Canal 实现，不恢复请求双写。新目录尚未运行可停机指回原目录；如果已推进消费位点，先停止两端并保留新旧备份，使用最新有效的完整 state 回迁，不能把切换前旧备份当成最新状态。无法确认状态或 binlog 覆盖范围时进入既有人工维护恢复。目录复制、启停及回退在隔离环境演练，不能直接在生产试验。

## 本轮验证与交付依据

- 请求边界：复用 ElasticsearchIndexStoreTest 的 HTTP 记录，验证一次边界校验后连续 bulk 不重复访问三个元数据接口；后续分页不重复校验，新一轮、重连及维护仍校验；失败校验不进入对应读写阶段。
- 失败与自动修复：覆盖逐项错误、删除文档不存在与索引不存在的区别、ACK 不确定、连接重试、旧候选被新事件作废、积压 DDL/TRUNCATE 先阻塞、空库保护、MySQL 查询失败不误删。保留原 SourcePositionGuard、P+T、连接取消及空事务配置的回归。
- 维护边界：保留增删改返回 3503 且不访问存储的测试，替换仅证明本地锁的旧测试；验证动态开关、未确认 repair 拒绝、check 无写入、维护入口正常结束。隔离维护演练故意持有一笔未提交真实题目写入，证明门禁只拒绝新请求，旧事务仍需完成或回滚；随后核对受控请求终态，或相关进程退出及数据库连接/事务消失，且原消费者退出后才运行独立维护 JAR。不以单进程测试声称跨进程互斥。
- 持久化：两套 Compose 只做配置渲染检查，生产默认值和环境变量覆盖均核对同一 state；在隔离真实环境新增旧目录迁往正式目录及回退演练，重建 Canal 容器后使用相同身份/位点续传，保留索引 UUID、映射和历史文档。
- 运行条件：沿用真实 MySQL 8.0、Canal 1.1.8、ES/IK 8.5.3 和隔离 JDK17；使用 _search 验证无积压时增删改 5 秒可见，不强制 refresh。原始命令、退出码、源码状态、延迟和日志保存在 .codex/canal-question-es-sync/，不覆盖旧证据，不用 skip 充当通过。

实施后沿用以下现有入口，使用尚未用过的标签；本轮规划阶段不执行这些测试或启动容器：

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s deploy/canal/tests -p 'test_*.py' -v
python3 deploy/canal/tests/run_maven.py canal-opt-unit-01 -pl oj-modules/oj-system,oj-modules/oj-friend -am test
python3 deploy/canal/tests/integration.py --run canal-opt-it-01
git diff --check
```

先扩展相关测试再执行上述命令，现有脚本通过不代表新增迁移场景已经覆盖。严格等级的独立规划评审、实施入口核验和实现验收分别基于修订后的文件；历史测试可作参考，受影响行为必须有优化后的真实结果。规划结构校验不等于评审通过，规划也不授权生产操作。
