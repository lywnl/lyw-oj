# 独立代码质量评审（第一轮）

- 风险等级：strict。
- 当前结论：Needs Work；三项可验证缺陷待修正。真实 Admin 集成尚未提供，本报告不作整体交付通过结论。
- 评审者：`/root/xxl_reconciliation_review`，独立于当前业务实现；未修改业务代码或测试源码，仅在项目 `.codex` 运行无网络启动探针并写评审材料。
- 时间：2026-11-11T11:22:00.241879+00:00。
- 基线：本轮已审阅业务文件哈希见 `.codex/canal-reconciliation-xxl-job/20261111-a1/evidence/independent-code-review-a2-baseline.json`；已有 a2 测试源码快照及命令见 `.codex/canal-question-es-sync/evidence/xxl-reconcile-20261111-a2-unit-sources.json`、同标签 command.json。

## 待修正项

### R1 — [P2] Feign 元数据解析先于安全校验，错误 URL 会泄漏凭据

位置：`oj-api/src/main/java/com/lyw/api/reconcile/RemoteQuestionReconcileService.java:11`、`oj-modules/oj-job/src/main/java/com/lyw/job/config/QuestionReconcileJobConfiguration.java:18`。

`@FeignClient` 将原始 target-url 直接交给 FeignClientsRegistrar；其 URL 解析发生在配置类实例构造与 `validateClient()` 之前。配置误含用户信息且 URL 格式有误时，框架生成带完整 URL 的启动异常，违背不在日志中回显连接凭据的要求。合成输入 `http://user:review-only-synthetic-password@localhost:INVALID` 已在真实 ApplicationContextRunner 上复现，异常消息包含该合成密码。

最小修正：将不回显参数值的目标地址校验放在 Feign 解析 URL 之前；或保证 Feign 元数据不接触未经校验的原始配置。增加真实 Spring 配置上下文测试，遍历非法端口、用户信息、查询等输入，检查完整异常因果链不含合成凭据，不能只调用属性对象验证方法。

证据：`.codex/canal-reconciliation-xxl-job/20261111-a1/review-probes/config-leak-1.log` 中 `SYNTHETIC_URL_CREDENTIAL_IN_STARTUP_EXCEPTION=true`；该探针仅在宿主 JDK 25 构建无网络 Spring 上下文，使用 a2 已编译产物，不启动服务、不读取真实凭据，不替代 JDK 17 业务验收。源码、class path 和命令记录在同目录。

### R2 — [P2] 首次分页创建后未交出的 PIT 清理失败绕过请求隔离

位置：`oj-modules/oj-system/src/main/java/com/lyw/system/questionsync/ElasticsearchIndexStore.java:175` 及其 page() 异常分支；`QuestionEsSyncRunner.java:196`。

首次 `index.page(null, ...)` 先创建 PIT。若随后 `_search` 失败，同时删除 PIT 也失败，page() 的 catch 吞掉删除错误，只抛原始查询异常。此时 page() 尚未返回，Reconciler.afterIndex 仍为空；Runner 的 `resetStrict()` 因而无法观察清理失败，会清空请求槽并按暂时故障恢复消费。实际遗留 PIT 没有触发约定的 cleanupFailed/BLOCKED 隔离，新一轮又可进入。

最小修正：让 IndexStore 内部尚未交出游标的清理失败明确传播到严格收尾逻辑，保留未清理状态并阻止接受下一轮；保留原始异常信息供诊断但不要仅依赖自动过期。增加“首次 PIT 创建成功 → 搜索失败 → PIT 删除失败”的确定性故障测试，验证 Future 失败、同步进入隔离、后续触发被拒绝。当前代码测试只覆盖已经交给 Reconciler 的游标关闭失败，不能覆盖该路径。

该项根据实际代码完整调用链确认，独立评审未启动额外 HTTP 服务复现；需由实施者在 JDK 17 隔离测试中增加故障注入证据。

### R3 — [P2] 分号参数内编码斜线导致网关与 Tomcat 识别结果不一致

位置：`oj-commom/oj-common-core/src/main/java/com/lyw/common/core/utils/InternalReconcilePath.java:16`。

输入 `/internal/question-index/reconcile;x=%2Fignored` 时，识别器先解码再按斜线拆段，参数内的 `%2F` 被当作新路径段，使 matches 返回 false；网关因此可能放行。实际 Tomcat 10.1.4 先剥离原始路径参数，再 URL 解码和规范化，最终得到 `/internal/question-index/reconcile`，Spring 映射到内部控制器。服务端对 servletPath 的检查仍要求内部令牌，所以这不是无凭据执行绕过；但持内部令牌的请求可以穿过网关，违反统一禁止公开转发的要求。

最小修正：保守覆盖参数剥离与 URL 解码两种顺序的目标路径，至少让上述路径及 `/system`、`/oj-system` 前缀变体在转发前被拒绝；同时回归普通接口不会被误挡。不宜只补一个字符串特例。

证据：`.codex/canal-reconciliation-xxl-job/20261111-a1/review-probes/matrix-path-1.log` 显示 `GATEWAY_BLOCKED=false`、`TOMCAT_TARGET=/internal/question-index/reconcile`、`MAPS_TO_INTERNAL=true`。探针直接调用真实 Tomcat 10.1.4 解析与 Spring PathPattern 类，未启动连接器或网络服务；源代码、命令与结果同目录保留。

## 其他已检查的边界

- 专用令牌与默认关闭：系统过滤器比较专用头，拒绝缺失、错误和多个值，普通用户令牌不能替代；客户端启动校验与执行器令牌区分。
- Feign 专属配置：无重定向、Retryer.NEVER_RETRY、NoOpLogger；固定长度空 POST 防止 HttpURLConnection 对 EOF 自行重发，已有真实本地 HTTP 测试验证单次调用。
- 单槽/取消：请求接收有同步锁，取消用原子标记，Reconciler 只由消费线程推进。成功清理前不释放槽，合作取消不进入整体停机分支；真实停机和暂时故障结束 Future。
- 结果语义：仅非空成功数据才回传 XXL-JOB 成功；无结果/业务失败/异常不误报成功。网络丢响应没有自动重试。
- 网关过滤位于 URL 改写和负载均衡之后、转发之前，同时检查请求和目标 URL；共同路径识别覆盖常规编码、分号、重复斜线和前缀，但组合编码参数存在 R3。现有证据是过滤器及 MockMvc 测试，最终仍须核对真实 HTTP 集成结果。
- 旧参数：normal 模式拒绝所有显式旧间隔；维护上下文保留，不开启新 HTTP 或执行器。

## 非阻塞建议与未完成交接

- Handler 当前只记录异常类名，403、409、503 都变成同一 RemoteReconcileException 文案。可以安全地附带已保存的 HTTP 状态，便于从调度记录区分令牌、占用与不可用，不必输出远端正文。
- 当前维护 README 仍包含旧本地 Cron/旧 0 关闭说明；这是实施中的未完成任务 3.1，应在最终核验前更新，不能按现状发布。
- a2 日志显示 oj-job 9、oj-system 69、gateway 3，共 81 项测试通过，无失败/错误/跳过；本轮独立评审审阅了这些日志和对应测试源码，没有重新执行全部用例。
- 新增测试探针已经阅读，但真实 Admin→执行器→Handler→系统→结果回调尚未验证。最终需补充实际结果、JDK 17 修复回归、当前代码指纹及清理结果，再由本评审者复核关闭 R1/R2/R3。

## 权限及操作范围

未修改业务代码、规划或任务勾选，未启动服务或容器，未修改线上状态；仅创建本报告、无网络审查探针及哈希记录。未执行 record-verify/check-verify，不把中间审查当作实现验收通过。


## a4 修复复核（最终收口前）

R1、R2、R3 的原始缺陷均已修复并有 a4 回归：条件先于 Feign URL 解析、首次 PIT 关闭失败向 Runner 传播并隔离、矩阵参数剥离/解码双路径识别。a4 的 15 份 JUnit XML 实际合计 86 项，失败/错误/跳过均为 0；真实 Admin 的成功、丢弃重叠调度、超时失败、存储失败/恢复及凌晨 02:00 Cron 均有实际执行记录。

测试隔离补救已核查：系统探针改为纯 AnnotationConfigServletWebServerApplicationContext，显式仅导入所需 Web 配置；脚本关闭 Nacos 并创建 --internal 网络。a4 两个运行日志均无 nacos.client，Admin 日志明确 JVM Asia/Shanghai，SQL 客户端使用 utf8mb4。此前 SpringApplication 仍触发默认 Nacos 初始化的失败轮次不得称为隔离通过，必须保留日志并说明。a4 的 5 个容器和网络已由只读 inspect 确认删除。

### R4 — [P2，待修正] 已关闭 PIT 的幂等删除被当作清理故障

Elasticsearch 8.5.3 的 ClosePointInTimeResponse 继承 ClearScrollResponse；其 status() 在 num_freed=0 时为 404，即使 succeeded=true。当前 closeCursor() 经通用 request() 把这种响应当异常。已有游标的后续 _search 暂时失败时，page() 内先关闭 PIT；Runner.resetStrict() 再关闭同一游标，合法的 404/true/0 被误判为 cleanupFailed/BLOCKED，临时故障因此不能恢复。

应只在 DELETE /_pit 收到可验证的 succeeded=true、num_freed=0 的 404 响应时视为清理成功；不要忽略任意 404。增加重复关闭、过期 PIT、普通错误 404，以及搜索失败后严格收尾可恢复的回归。官方源码和 URL/哈希保存在 review-probes/pit-close-official-source.json；尚未因此运行新的业务服务。

当前代码结论仍为 Needs Work，等待 R4 修复回归及最终基线核对；先前 86 项及 a4 集成通过不能覆盖该新发现的分支。
