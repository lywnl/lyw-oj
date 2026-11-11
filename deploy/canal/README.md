# 题目索引同步运维

`tb_question → ROW/FULL binlog → Canal 1.1.8 TCP → oj-system → idx_question`。增量直接使用完整行，不查询题目表；启动/周期元信息检查、基线核对和对账允许读 MySQL。原 RabbitMQ 判题链路不参与题目索引同步。

## 配置和权限

先检查实际 MySQL 8.0 的 `log_bin`、`binlog_format=ROW`、`binlog_row_image=FULL`。Compose 固化相同值不代表需要重启现有数据库。生产账号操作、配置变更、启停和上线按目标环境另核授权。

`prepare.py` 只生成配置和待审阅 `account.sql`，不执行 SQL。开发配置可放项目 `.codex/`；生产默认使用 `deploy/prod/canal/config`，state 使用 `deploy/prod/canal/state`，备份可放受限的 `deploy/prod/canal/backups`。这些生成目录已忽略 Git，凭据文件权限为 600。也可通过环境变量指定受限的正式目录。

| 环境变量 | 用途 |
|---|---|
| `CANAL_DB_NAME` | 业务库；开发默认 `lywoj`，生产必填，不能用 Nacos 的 `MYSQL_SERVICE_DB_NAME` 推断 |
| `CANAL_DB_HOST` / `CANAL_DB_PORT` | Canal 到 MySQL 的地址，默认 `oj-mysql-server:3306` |
| `CANAL_DB_USERNAME` / `CANAL_DB_PASSWORD` | 专用复制账号，默认用户名 `canal_sync`，密码必填 |
| `CANAL_MASTER_FILE` / `CANAL_MASTER_POSITION` | 完成基线核对的明确起点 P，应用首次订阅使用 |
| `CANAL_SOURCE_JDBC_URL` | 同一 MySQL 业务库的 JDBC 地址，供应用检查源位点 |
| `CANAL_STATE_DIR` / `CANAL_CONFIG_DIR` | 生产默认 `./canal/state` / `./canal/config`，相对于生产 Compose 所在目录解析；Canal 与应用必须指向同一 state，环境变量可覆盖 |
| `CANAL_SYNC_ENABLED` | 默认 `false`，只在一个指定实例开启 |
| `QUESTION_MAINTENANCE_ENABLED` | 开启后题目新增、修改、删除返回 3503；其他管理功能不受影响 |

MySQL 专用账号使用 `mysql_native_password`，只授予全局复制权限和指定业务库 SELECT，不改变全局认证方式。数据库名中的下划线在 GRANT 中转义，避免被当作通配符。生成 SQL 由操作方审阅执行；已有账号先核对，不静默复用未知权限。

当前版本的 Canal 会把认证密码放入 `COM_REGISTER_SLAVE` 的 `report-password` 字段，MySQL 8.0 该字段最多 32 字节。因此采用不超过 32 个 UTF-8 字节的强密码，建议随机 ASCII；生成器和应用拒绝超限值，不截断。这是副本注册字段限制，不是 MySQL 登录密码的通用上限。生成 SQL 按 MySQL 默认字符串转义规则编写；随机字母数字密码不受反斜线模式影响。

Canal 的 TSDB 在应用表白名单之前枚举库，配置显式排除 `information_schema`、`performance_schema`、`mysql`、`sys`，无需为此扩大 SELECT 权限。订阅使用 Canal 的 Perl 正则语法，不使用 Java `Pattern.quote`。保留 DDL、事务边界及空事务，关闭静默忽略表错误。启动脚本把 `MysqlConnection` 固定在 WARN，避免它的 INFO 注册日志输出密码。

在题目写入及结构变更均暂停时，从同一源会话读取 `SHOW MASTER STATUS` 的 P，以及 `SELECT CAST(UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3))*1000 AS UNSIGNED)` 的源时间 T，再生成配置（示例值必须替换）：

```sh
python3 deploy/canal/prepare.py --env dev --output-dir .codex/canal/dev/config --source-file mysql-bin.000001 --source-position 157 --source-timestamp 1794247259000
```

生产从项目根执行时，将输出目录改为 `deploy/prod/canal/config`，并明确提供环境中的业务库和复制凭据；P、T 仍须替换成实际核对值：

```sh
python3 deploy/canal/prepare.py --env prod --output-dir deploy/prod/canal/config --source-file mysql-bin.000001 --source-position 157 --source-timestamp 1794247259000
```

新部署先准备目录和权限；已有部署先完成下文的完整迁移，不能因 Compose 换了默认目录而直接启动一个空 state。应用只读挂载到 `/canal-state`，Canal 读写挂载到 `/state`。

开发和生产 Compose 的 Canal 服务均位于 `question-sync` profile。开发发布 `127.0.0.1:11111`；生产只走容器网络。内部 MySQL 端口是 3306，宿主机开发 JDBC 使用宿主机映射端口。Canal Server 和 Java 客户端固定为 1.1.8，ES/IK 为 8.5.3。

## 应用设置

两个 bootstrap 环境配置均提供同步入口。Nacos 或环境变量可以覆盖；消费者默认关闭。`canal.sync.database` 必须与业务数据源和复制源的 catalog 一致，两条连接的 `server_uuid` 也必须一致。

| 应用参数 | 默认值或要求 |
|---|---|
| `canal.sync.enabled` / `mode` | `false` / `normal` |
| `canal.sync.destination` / `client-id` | `question-sync` / `1001`，重启保持身份 |
| `canal.sync.host` / `port` | `127.0.0.1` / `11111`；生产 Compose 覆盖主机名 |
| `canal.sync.state-directory` | 必填；生产只读挂同一个持久目录到 `/canal-state`，宿主机运行时提供绝对路径 |
| `canal.sync.initial-file` / `initial-position` | 首次无有效游标时的明确 P，不能取代已有 meta.dat |
| `canal.sync.source-jdbc-url` / `source-username` / `source-password` | 专用复制账号的 JDBC 连接，不是 Nacos 数据库 |
| `canal.sync.batch-size` / `idle-millis` | `100` / `1000`；空闲时等待 1 秒再拉取，有工作时连续处理 |
| `canal.sync.timeout-millis` / `retry-max-millis` | `5000` / `30000`；建连有可取消的总时限 |
| `canal.sync.position-check-millis` | `30000`，检查日志文件、范围、ROW/FULL；不查询题目行 |
| `canal.sync.page-size` | `500`；每次对账分页大小 |
| `canal.sync.reconcile-interval-millis` | 已废弃；normal 同步启动时任何显式值（含 0）都会提示迁移错误，必须移除 |
| `question.maintenance.enabled` | `false`；可由既有 Nacos 配置方式开启 |

`canal.sync.*` 按启动配置使用；变更连接参数或启停消费者时，停止并重新启动指定实例。不能只热更新 enabled=false 就认为线程已退出。题目维护门禁则在每次写请求读取 Environment，可随既有配置刷新生效。

每日对账使用固定北京时间，独立于宿主机或 JVM 默认时区。原消费循环通过固定 Spring Cron 表达式 `0 0 2 * * *`（秒、分、时、日、月、周）计算下一次 02:00，再判断是否到期；不增加通用配置或调度线程。仍逐页让出增量消费机会，实际开始可能受当前请求耗时影响。新进程在 02:00 之后启动时等待次日，不补跑停机期间的历史轮次；已经安排本轮后发生短暂断线，重连不推迟当天的触发时间，恢复后可补跑当前到期轮次。上一轮跨过下一触发点时继续原轮次，结束后安排下一未来时刻，不并发补跑历史任务。

每日对账改由现有 XXL-JOB → `oj-job` → 唯一活动 `oj-system` 消费实例触发，应用不再计算本地 Cron。新入口默认关闭；启用及迁移步骤见 [XXL-JOB 对账维护说明](reconciliation-xxl-job.md)。必须移除 Nacos/环境中的旧间隔（包括值 0），关闭每日任务改为在 XXL-JOB 停用任务。手动 `check/repair` 和增量拉取独立保留。

应用数据源沿用已有配置，并设置有界的连接池获取时间。`question.maintenance.enabled` 是本进程写入门禁；它不能替代多个实例及外部 SQL/导入程序的停写协调。

ES 必须禁止目标索引隐式创建：`action.auto_create_index: "-idx_question,+*"`。已有其他索引策略时保留并合并，目标拒绝规则应优先。两端 Repository 均关闭自动建索引。应用检查既有十个字段类型及日期格式，保留原分词器；缺失/不兼容映射进入维护。ES 账号需读写题目索引、查询映射及集群设置；显式创建索引只在维护入口执行。

完整 ES 元数据校验在首次连接、每次重连、新一轮自动对账和 check/repair 入口执行，显式创建后也会核对；稳态每批写入和后续分页不重复这三个请求。每次 bulk 仍逐项检查结果，索引缺失错误不能当作文档不存在的删除成功。运行中映射/策略异常在约定校验边界或实际请求失败时发现；对账关闭时不承诺周期映射检查，索引或策略变更应进入维护。

## 首次切换

1. 核对实际库、网络、ROW/FULL、ES 映射和访问权限，准备复制账号、配置和持久目录。同步保持关闭。
2. 开启题目维护门禁，暂停全部外部导入/直接 SQL 写入，等待在途操作结束。发布去双写版本，确认管理端、用户端旧 ES writer 均已退出。
3. 在题目稳定时同时记录 P 和源时间 T 并生成配置。**先启动 Canal Server，应用消费者仍关闭**；`check` 需要 TCP 端口可达，但不会订阅、ACK 或 rollback。
4. 执行 `check`。数据一致时不写 ES；有差异才执行 `repair`。已有索引只定点写删，保留 UUID 和映射；索引缺失才显式创建导入。`repair` 也可在 Canal Server 停机时执行。
5. 开启指定的一个消费者，确认实际事件消费、ACK 和搜索可见性，再恢复题目写入。不能仅凭 TCP 可连或 RUNNING 判定解析器健康；核对 Server 日志与真实增删改结果。

维护命令使用同一 oj-system JAR 的一次性非 Web 模式，不新增远程接口或常驻服务：

```sh
sh deploy/canal/maintain.sh check /绝对路径/oj-system-1.0-SNAPSHOT.jar
sh deploy/canal/maintain.sh repair /绝对路径/oj-system-1.0-SNAPSHOT.jar --confirm-maintenance
```

凭据从已有 Nacos/环境配置读取，不放 Java 命令行。`check` 退出码 0 表示一致、2 表示有差异、1 表示检查失败；脚本参数错误为 2。`repair` 的确认表示操作方已暂停所有题目写入、旧 writer 和增量消费者，不能把新进程里的本地锁当作跨实例互斥。

维护进程不设置题目维护开关，也不尝试用本地锁排空其他进程。`question.maintenance.enabled` 必须在实际在线实例生效；脚本传入的 `canal.sync.enabled=false` 只阻止维护进程自己启动消费线程。3503 只拒绝新写请求，已进入服务的请求仍需完成或回滚。

执行 repair 前先识别所有写题来源及其数据库连接/事务：

1. 暂停外部 SQL、导入和相关后台写入，在在线业务实例启用门禁并验证题目写接口返回 3503。
2. 能跟踪受控请求时，核对请求/导入的最终结果，并确认已识别的连接不再执行写入、事务已提交或回滚。客户端超时不是执行结束证明。
3. 没有可靠请求观测能力时，在获准短维护窗口停止相关写题应用/导入进程及原消费者；用进程管理器状态或 `docker inspect` 的 `State.Running=false` 确认退出，再核对数据库侧对应连接和事务消失。不能仅等待固定秒数。
4. 确认没有题目写入和活动消费者后，才传入 `--confirm-maintenance`。共享账号或网络使来源无法区分时先核对来源，不扩大为终止整个数据库。查询权限必须能看见所核对的来源。

以下为数据库核对模板，必须将占位符替换成事先记录的真实连接 ID；不在日志中输出整条题目 SQL 或用例：

```sql
SELECT ID, USER, HOST, DB, COMMAND, STATE
FROM information_schema.PROCESSLIST
WHERE ID IN (<已核对的连接ID>);

SELECT trx_id, trx_mysql_thread_id, trx_state
FROM information_schema.INNODB_TRX
WHERE trx_mysql_thread_id IN (<已核对的连接ID>);
```

发布消费者时必须先让旧进程退出，再启新进程。关闭回调只在消费和对账退出后完成；若仍提示线程退出中，不启动接替实例。不提供自动主备或滚动双活动消费。

## 生产目录迁移与回退

修改默认路径不会自动搬运数据。已有环境按实际挂载核对源目录，运行操作须有该环境授权：

1. 按维护步骤暂停题目及结构写入、排空请求，停止消费者和 Canal Server，记录当前身份与有效位点。
2. 完整备份原配置与 state；复制全部 `meta.dat`、TSDB 文件、`metadata-established` 等内容到正式目录。保留权限并逐文件核对，保留原目录作为回退依据。不要用 `prepare.py` 生成的新空目录替代原 state。
3. 核对生产 Compose 中 Canal 的 `/state` 和应用的 `/canal-state` 来自同一实际目录，配置来自迁移后的目录。挂载源变化时按新配置重新创建 Canal 和应用容器，仅 restart 旧容器不会更新绑定目录；新应用保持题目门禁开启、消费者关闭，完成 check 后才启用唯一消费者。开发 `.codex` 和生产正式目录不可混用。
4. 先启动 Canal Server、执行只读 check，再启动唯一消费者；确认真实事件续传和搜索结果后恢复题目写入。普通目录迁移复用原位点，不重置 P、清索引或自动 repair。
5. 回退前再次停消费者和 Canal。如果新目录尚未使用，可指回原目录；已经推进位点时，保留新旧备份，携最新有效完整 state 回迁，不能直接恢复陈旧副本。状态不明或位点失效时转入下文基线恢复流程。

优化版本回退到此前 Canal 版本时，仍保留去双写的业务边界。数据复制和回退必须先在隔离环境演练，不在生产试探。

## 重试、对账和人工恢复

完整覆盖保留 NULL 语义。ES bulk 逐项成功后才 ACK；删除不存在的文档成功。部分写成功、网络失败和未知 ACK 结果走至少一次重放，可能短暂看到旧版本，最终追平。rollback 不撤销已写 ES 的内容。

每页对账先生成候选，再记录源位点屏障；消费到该位点后才修复，期间变化的 ID 放弃本页候选。所有增量和对账写入均在同一消费线程执行，每页让出消费机会。无关表事务可以推进屏障；某些无关 DDL/ROTATE 不能提供可证明的末尾位点。候选等待超过 5 分钟会告警、关闭 PIT 并放弃本轮，下一计划时刻重新扫描；PIT 保留时间为 10 分钟。每日 02:00 是计划触发时刻，不是修复完成时限；临时故障重试和阻塞处理沿用现有规则。

自动对账发现主库空且 ES 有数据时仅报告差异，不清空索引。数据库查询/分页失败也不能解释成题目不存在。显式维护 repair 在完成核对后可以删除多余文档。

目标 DDL、TRUNCATE、完整行缺列/类型错误、失效位点和索引映射错误会阻塞增量与自动修复。Canal 放行但无法可靠归属的数据库级 QUERY DDL 也保守阻塞；共享 MySQL 的建库/删库操作需要核对这一边界。普通无关表 DML 不会写入题目索引。

正常重启复用原 state（meta.dat、TSDB 和建立标记）及客户端身份，允许重复。首次只包含正确订阅身份、尚未建立游标的元数据可沿用 P；损坏 JSON 等待重试，已有游标丢失不能冒充首次启动。Canal parser 的错误未必透传 TCP，周期位置检查用于补充这一缺口，不能把空批当成已追平。

失效位点、DDL 之后的受控恢复：

1. 停止消费者与自动对账，开启题目维护并确认所有写入停止。
2. 停止 Canal Server，完整备份原 state（含 TSDB），保留错误和原游标。
3. 修复表结构/映射前先核对影响；运行 repair 建立当前数据基线并记录新的有效 P 和源时间 T。repair 不改 Canal 元数据。
4. 将新 P 和 T 同时提供给生成器（`master.timestamp` 缺失会让 1.1.8 为 TSDB 回退扫描旧事务并重放旧 DDL）。若需要从新 P 开始，使用**新的空 state 目录**生成配置并重新创建 Canal 容器，使挂载指向新目录。已有 meta.dat 优先，单改 initial-file/position 不会覆盖它；不要删除旧目录来隐藏问题。
5. 先启动 Server 并 check，再开启唯一消费者、验证实际同步，最后恢复写入。

回退应用版本前，先停消费者和对账，确认线程退出后再恢复旧版本，避免两个 writer 同时工作。保留索引和 state；如旧版本恢复了请求双写，其后重新启用 Canal 必须重新走受控切换。

同步每 30 秒记录最近成功时间、最近 ACK、重试和差异/修复计数，异常记录阻塞原因。应用日志不输出凭据或题目用例内容。

## 隔离验证

需要 Docker、Python 3 和项目现有 IK 8.5.3 插件。测试只创建专用标签的 MySQL/Canal/ES 容器和网络，临时源码、依赖缓存及证据均在 `.codex/canal-question-es-sync/`，不读取生产数据。测试使用固定版本镜像，不用既有服务替代。

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s deploy/canal/tests -p 'test_*.py' -v
python3 deploy/canal/tests/run_maven.py canal-unit-01 -pl oj-modules/oj-system,oj-modules/oj-friend -am test
python3 deploy/canal/tests/integration.py --run canal-it-01
```

标签用小写字母、数字和短横线。`--keep` 只用于失败调查；清理使用 `--run 同一标签 --cleanup-only`，脚本按登记 ID 和标签删除资源，不执行全局 prune。构建使用 JDK17 隔离容器，每次拥有独立目录。

集成涵盖保留基线、_search 延迟（不强制 refresh）、MySQL 查询审计、真实 ES 停机时的业务回归、容器重建、ACK 前进程终止、DDL/非法格式/位点失效、对账以及完整维护 JAR 入口。部分写入和 ACK 不确定由测试适配器在真实存储边界注入；ES 请求读超时通过本地测试代理在真实 bulk 成功后延迟响应，验证真实 SocketTimeoutException；逐项 bulk 异常另由 HTTP 测试验证。最终通过情况以 OpenSpec 验收报告及原始命令日志为准，不以测试脚本存在代替执行成功。
