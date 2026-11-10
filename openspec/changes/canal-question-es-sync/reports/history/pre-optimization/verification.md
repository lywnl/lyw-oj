# 实现验收

- 日期：2026-11-10（Asia/Shanghai）。
- 变更：canal-question-es-sync；风险等级 strict；核验方式 machine。
- 结论：**Ready for Archive**。本次只完成实现和验收，不执行归档、提交、推送或生产部署。
- 依据：已通过的规划基线及 implementation_started 记录；当前应用、配置、部署脚本和测试。37 个生产文件的独立复核聚合 SHA-256 为 `2f62a433e952585c6652933a9eeb34f28d50ed23f93ecff5300d01026586050f`，明细见 `reviews/independent-code-files.json`。

## 实现结果

正常增量从 Canal 的 ROW/FULL 行事件构建完整文档，不读取题目表。增删改按源顺序合并，ES bulk 逐项成功后才确认；失败可重放。主键变化、空值、大整数和本地 DATETIME 均有验证。单消费者、可取消的建连/DNS 时限、退出边界和异常阻塞已经落地。

管理端题目去掉 ES 双写，用户详情及判题快照改用 MySQL。两端题目 Repository 关闭自动建索引，维护门禁返回 3503。check/repair 使用同一 JAR 的必要组件上下文，避免加载演示表、普通控制器和 Web 鉴权组件；已有兼容索引保持原映射和 UUID。

对账和增量分时串行，使用源位点屏障及变化 ID 作废规则；数据库读取失败不产生删除，自动空库保护不清索引。源 DDL、TRUNCATE、不完整行、无效位点、映射错误等阻塞同步及自动修复。

7 项需求、12 个场景及 8 项任务的详细实现、测试和证据映射统一维护于 `verification-manifest.json`，共 27 条，不另复制第二份映射表。

## 真实执行结果

| 验证 | 结果与原始证据 |
|---|---|
| JDK17 单元及业务回归 | `unit-13` 退出 0；56 条记录中 53 项执行通过，3 个真实集成入口按条件跳过。原始日志、JUnit 和源码快照位于 `.codex/canal-question-es-sync/evidence/` |
| 隔离 MySQL 业务回归 | `it13-friend-real` 14 项执行通过、0 跳过；其中 2 项使用真实 MySQL，其余为提交回执/结果查询等回归。实际停掉 ES 后，详情和判题装配仍读取主库 |
| 完整真实同步链路 | `integration/it13/run.json` 退出 0，46 项场景检查通过；使用独立 MySQL 8.0.40、Canal 1.1.8、ES/IK 8.5.3，未连接生产数据 |
| 搜索可见性 | 新增 960.5 ms、修改并清 NULL 1077.2 ms、删除 908.7 ms；连续修改、主键变更、删除后重建均不超过 1077.2 ms。全部通过 `_search`，未强制 refresh |
| 无回查 | 对账关闭时 QuestionReader 调用为 0；MySQL general_log 中应用账号的题目 SELECT 计数为 0 |
| 真实 ES 响应读取超时 | ES 已完成 bulk 后，本地测试代理延迟响应，实际出现 SocketTimeoutException，耗时 5137 ms；失败批次 4 未确认（此前 ACK 3），重放后 ACK 5，追平超时期间的新修改 |
| 其他故障和持久化 | 真实 ES 停机恢复、ACK 前杀掉 JVM 容器、Canal 容器重建复用原卷均通过；部分写和 ACK 不确定由测试适配器在真实存储边界注入，bulk 逐项错误另有 HTTP 测试 |
| 维护和非法输入 | 旧索引差异修复、积压 TRUNCATE 后重插入、ALTER、RENAME、多表 DROP、MINIMAL UPDATE/DELETE、STATEMENT、全局 ROW/FULL、无效/丢失位点、缺失/不兼容索引、真实 MySQL 读取权限失败均按约定处理 |
| 完整维护入口 | `maintain.sh → 可执行 JAR → Spring → check/repair` 均输出 COMPLETE、退出 0；原始日志为 it13 的 command-0169.log、command-0171.log |
| 配置及静态检查 | 4 项 Python 配置测试通过；两套 Compose 渲染、两份 shell 语法检查及 git diff --check 通过 |
| 资源清理 | 最终核对本任务标签的容器数与网络数均为 0；原始证据、构建产物和依赖缓存保留，见 `final-cleanup.json` |

最终维护包的 31 个应用输入、单元测试及两个集成探针的应用源码快照均与评审基线匹配；6 个部署文件另外纳入文件指纹及静态验证。三个门控入口随后均有独立真实集成执行，未将 skip 当作通过。

## 发现、修复及历史失败

独立评审见 `reviews/independent-code-review.md`，评审者 `/root/canal_review` 未编写实现、部署脚本或测试。当前没有未解决的阻塞发现。

实现及集成中修复了 DDL 表头过滤、首次空游标、停止回调时机、连接超时、取消后的晚到连接、PIT 等待、空库差异计数和 HEAD 404。真实部署还发现并修复了：TSDB 枚举系统库的权限问题、Canal Perl 正则不支持 Java 的引用语法、COM_REGISTER_SLAVE 密码字段的 32 字节限制、TSDB 缺少时间戳导致回退重放旧 DDL，以及完整维护进程错误加载普通模块的问题。

新基线同时记录源文件/偏移 P 和同源 Unix 毫秒 T；使用新空 state 时同时设置 master.timestamp，避免回退扫描旧 DDL。真实验证确认旧 DDL 纳入基线后新 UPDATE 正常可见，之后的新 DDL 仍然阻塞。应用不增加“忽略历史 DDL”规则。

历史 compile/unit/it01～it12 的失败记录保留，未改写为通过。包括测试构建目录共享、探针控制和故障注入错误、随机端口变化、MySQL 临时初始化进程误判等测试工具问题；也包括上述真实实现/配置缺陷。it12 的首次元数据丢失测试前置条件不足，补验先建立真实 ACK 与持久化标记后通过。最终 it13 在同一套当前代码上连续完成全部检查，退出码 0。

it13-probe-1 的 137 是计划内 ACK 前杀进程的退出码，由最终主库/ES 一致性和续传结果核验，不伪装成正常退出。历史失败日志和受影响复测都可以从 `.codex/canal-question-es-sync/` 追溯。

## 交付限制及操作边界

同步默认关闭。单活动消费者由部署保证；canal.sync 参数按启动配置使用，不能仅热更新 enabled=false 就认为已退出。题目维护门禁逐次读取 Environment，但不能替代跨实例及外部导入的停写协调。

重放允许短暂旧版本；不提供自动选主、源版本列或墓碑。遇 DDL、无效位点或不兼容映射，按 `deploy/canal/README.md` 进入维护；不得自动清索引或跳到当前。对账 30 分钟是调度间隔，无法证明追平的候选等待 5 分钟后放弃本轮。

上述延迟和恢复结论仅适用于此次隔离验证条件。生产库、凭据、权限、网络和已有配置尚未接入验证；生产账号创建、配置变更、启停及上线需按既定授权范围执行。
