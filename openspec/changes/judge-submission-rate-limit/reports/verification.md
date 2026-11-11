# 当前实现验收：全局每秒 3 次

- 风险等级 strict；核验方式 machine；结论 Ready for Archive。
- 最新用户要求与规划来源为 .codex/plans/redisson-global-three-20261111-091713-6359e1d3.md；当前 13 项任务均完成，8 项需求、24 个场景、13 项任务共 45 个映射条目见 verification-manifest.json。
- 当前独立规划和代码增量评审均 PASS：reviews/independent-planning-review-global-three.md、reviews/independent-code-review-global-three.md。未修改部分复用原独立评审及基线哈希。

Java 与 bootstrap 默认全局值均为 3/1s，用户保持 1/5s。实际 Docker/JDK 17 执行 53 项全部通过，含 14 项真实 Redis、39 项配置/单元/MockMvc/提交查询回归；2 项原有 MySQL 集成依原开关跳过。默认前三次通过、第四次拒绝及周期恢复、双客户端共享三个许可、存量 2 次状态不会被新配置覆盖、维护清理后初始化为 3 均有真实验证。

本轮测试输入 334 份与工作区一致。六个业务配置/测试/维护文件为本次增量，依赖版本、限流算法、业务 API、用户规则与容器池配置均保持原状。旧依赖树因 POM 未变化继续引用旧报告，新版真实客户端并存测试重新通过。

保留报告：.codex/reports/redisson-global-three-20261111-091713-6359e1d3.md。原始 XML、日志、命令、输入哈希和本轮 diff 已收口，历史 2 次实施与队列报告未改写。本轮两个容器、独占网络和新增 Redis 镜像已清理，既有 Maven 镜像及 8 个原有容器保留；临时目录已删除，无关文件哈希不变。

没有操作真实业务 Redis、停止或部署业务服务，没有执行新的题目吞吐或完整 MQ/MySQL 链路压测；每秒 3 次仍是试运行参数。部署时须沿用停服统一清理逻辑限流器后重建的维护流程。用户没有授权提交、推送、发布或归档，本轮不执行这些操作。

## 之前每秒 2 次版本的验收（历史记录）

# 实现验收报告

- 变更：judge-submission-rate-limit
- 风险等级：strict；元数据位于 x-workflow.workflow_profile。
- 核验方式：machine。
- 结论：Ready for Archive。
- 当前用户授权不包含归档、提交、推送或部署，本轮不执行这些操作。

## 基线与覆盖

已复用有效规划评审与真实 start-apply 记录。当前 11 项任务均已完成；详细映射保存在 reports/verification-manifest.json，包含 8 项需求、24 个场景和 11 项任务，共 43 个可核验条目。实现/测试路径均为当前项目内的现存文件，原始证据统一指向保留运行报告。

实现集中于 oj-friend 的独立客户端、配置、SubmissionRateLimiter、prepareSubmission、维护说明与测试；公共 ResultCode/GlobalExceptionHandler 增加三类错误及 HTTP 状态。未修改已有容器池实现。独立代码报告的 16 个实现/配置/测试/文档文件与最终 build-04 相符，构建输入与工作区没有漂移。

## 实际检查与结果

最终 Docker/JDK 17 build-04 执行 Maven install 和 dependency:tree，退出码 0。实际执行 53 项全部通过，其中 14 项真实 Redis，39 项配置、单元、HTTP 和既有回归；2 项旧 MySQL 集成按原开关跳过，未计为通过。

实际依赖树确认 oj-friend 使用 Redisson 3.52.0、Netty 4.1.127.Final、Lettuce 6.2.2.RELEASE、Spring Data Redis 3.0.0。真实 Redis 测试验证 ACL 认证/数据库 5、两个独立客户端共享额度、默认冷却与恢复、用户关联状态过期、普通重建不重置、停服维护模拟与其他数据保留。

并发观察为同用户 20 次仅 1 次通过、16 个用户跨两客户端合计 2 次通过。Redis 暂停响应及真实 TCP 转发断连均得到 HTTP 503，并恢复后继续通过检查。故障耗时包含 Servlet 测试调用开销，不用于吞吐结论。

git diff --check、目标变更 CLI strict 校验、规划指纹检查均通过。详细命令、时间、退出码和完整最终 JUnit XML 见保留报告及清单。

## 异常、独立评审与限制

- 首轮构建因容器解析 Maven 中央仓库失败而退出 1，尚未编译；仅在本轮隔离 Maven 配置使用用户提供的代理后恢复。失败记录保留。
- 独立规划评审与独立代码评审均为 PASS，评审者 /root/submission_rate_review 未撰写实现。独立代码依据为 reviews/independent-code-review.md。
- 本轮没有进行真实 MySQL/MQ/网关/浏览器完整链路或真实题目吞吐测试；每秒 2 次仍为试运行值。
- SSL 启用/验证与不支持配置的拒绝通过配置测试验证，未搭建真实 TLS Redis；不同主机时钟偏差和 Redis 故障转移不在测试范围。
- 生效配置适配依赖固定 Spring Data Redis 3.0 内部配置字段；有通用构造器和拒绝分支测试，版本不兼容时明确失败，不静默回退 localhost。

## 证据保留与清理

保留报告：.codex/reports/redisson-submit-rate-limit-20261110-215050-dc890d08.md。

原始构建日志、最终全部 JUnit XML、命令、依赖树和输入哈希已在清理前整合并核对。历史临时路径仅为执行来源，不作为当前存在的独立证据引用。普通测试认证值如出现在早期日志中已按报告标记脱敏，最终验证字节哈希同时保留。

本轮 5 个容器、1 个网络和独占新增 Redis 镜像已清理；既有 Maven 镜像保留。本轮工作目录及空临时父目录已删除，限流测试服务和构建进程已退出。原有 64 个改动文件哈希均未改变，测试前 8 个容器均仍存在。清理操作的实际退出码及状态记录在保留报告中。

最终报告和计划来源保留，供用户审阅。验收机器记录在证据和清理结果完成后生成，不把文件指纹当作测试或独立评审的替代。

