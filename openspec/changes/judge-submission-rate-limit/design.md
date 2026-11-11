# Design

## Context

见 proposal.md。两个提交入口均在 UserQuestionServiceImpl.prepareSubmission() 中完成基础校验、鉴权、查询题目、生成提交 ID 和等待缓存写入。公共异常处理器目前只返回 Result，未设置业务异常的 HTTP 状态。项目为 JDK 17 / Spring Boot 3.0.1，已有 StringRedisTemplate、LettuceConnectionFactory 和 StateCacheService，用户确认 Redis 是单实例。

## Goals / Non-Goals

Goals：实现 specs/judge/submission-rate-limit/spec.md 的两层规则与拒绝契约，保证现有查询和放行后的受理逻辑兼容；取得独立评审、JUnit/MockMvc 和真实 Redis 证据。
Non-Goals：不增加新的调度系统、任务在途配额、IP/查询限制、热更新、管理 HTTP 接口、数据库迁移或容量结论。本轮不操作业务 Redis。

## Decisions

### 1. 独立 Redisson 客户端，复用生效连接元数据

在 oj-friend 局部依赖管理中固定 redisson 3.52.0 和 Netty BOM 4.1.127.Final。使用普通 Redisson 依赖和显式 Bean，不使用会替换 RedisConnectionFactory 的 starter。新客户端只由限流服务使用，现有缓存仍通过 Lettuce。

从实际注入的 LettuceConnectionFactory 获取 RedisStandaloneConfiguration 及客户端 SSL 设置，避免再解析一份配置导致 URL、认证或数据库优先级不一致。校验单机拓扑，拒绝其他连接工厂或拓扑。复制 host/port/database/username/password、SSL 启用和验证配置；不输出凭据。IPv6 地址加方括号。优先复用现有 SSL 语义，不能无声降低验证。

显式启用 Config.lazyInitialization；连接/命令超时各 1000 ms、retryAttempts=0、connectionPoolSize=8、connectionMinimumIdleSize=1，transportMode=NIO，通用 StringCodec。Bean 销毁时 shutdown。客户端构造不主动初始化限流状态，避免 Redis 暂不可用被新增组件放大为启动阶段强制连接。

### 2. 固定配置快照与安全名称空间

新增 SubmissionRateLimitProperties，前缀 judge.submit-limit，字段：
- environment：由 bootstrap 中的 spring.profiles.active 注入，默认 dev；仅允许字母、数字、下划线和连字符，长度 1～64，避免维护匹配器扩大范围。
- userInterval：5s；用户每周期许可数固定 1。
- userIdleTime：10m，必须大于等于 userInterval。
- globalPermits：3；globalInterval：1s。

Duration 要求可转换为正毫秒，许可数必须为正。构造限流服务时验证并保存配置快照；不使用 RefreshScope。两层都用 OVERALL，用户键为 oj:<environment>:submit:user:<userId>，总量键为 oj:<environment>:submit:global。环境不包含服务实例、接口或题目，保证应共享的额度不会分裂。

### 3. 明确顺序与副作用边界

新增 SubmissionRateLimiter.check(Long userId)，只依赖 RedissonClient 和有效配置。按照用户初始化 → 用户 tryAcquire → 全局初始化 → 全局 tryAcquire 顺序执行；初始化用 trySetRate，已有规则返回 false 是正常情况。

用户四参数初始化传入空闲存活时间，总量三参数初始化不设置过期。每次请求都允许幂等初始化，因此客户端重建和用户过期后能够继续工作。规则的具体恢复算法由固定版本 RRateLimiter 提供，不自写 Lua 或提前读取剩余许可；两个检查各自原子但不构成跨规则事务。

prepareSubmission 将 currentUserId() 的结果传入 check，位置在现有 Java 类型和基础参数校验之后、assembleJudgeSubmitDTO 之前。先取得用户许可后遇到任何下游失败都不补偿。此行为已获用户确认。

### 4. 保持现有 Result，新增错误码的真实 HTTP 状态

ResultCode 新增：
- FAILED_SUBMIT_FREQUENT，3602，提交过于频繁，请稍后再试。
- FAILED_SUBMIT_BUSY，3603，当前提交请求较多，请稍后再试。
- FAILED_SUBMIT_LIMIT_UNAVAILABLE，3604，提交服务暂时不可用，请稍后再试。

继续抛 ServiceException，公共处理器只对这三个枚举映射 429/429/503，返回 Result.fail 对应枚举，data=null。其他业务异常继续原有处理。

RedisException（包含连接与超时故障）转换为 3604；普通规则拒绝直接抛 3602/3603，不被 Redis 故障分支误分类。日志记录原因类别，普通超限无异常堆栈，故障不记录异常中可能附带的认证信息或用户源码。

### 5. 测试采用隔离 Redis 与现有轻量测试方式

沿用现有 JUnit 5、Mockito 和 Spring Test；对 Controller/Advice 使用 standalone MockMvc，不启动 Nacos、数据库、RabbitMQ 或整个 Spring Boot 应用。

真实 Redis 用例采用独立开关 oj.rate-limit.integration=true，必须显式提供测试 host/port 与含本轮标识的环境前缀；不读取 bootstrap 的业务连接信息。Docker Redis 7.2-alpine 为本轮独占资源；Maven/JDK 17 在既有 maven:3.9.9-eclipse-temurin-17 镜像内执行，源码快照、Maven 本地仓库与日志均在本轮 .codex 目录。新测试与既有 oj.integration 门控隔离，不因本轮开关启动 MySQL 测试。

两套独立 RedissonClient 验证 OVERALL 跨客户端共享，Lettuce/StateCacheService 同时连接该 Redis 验证依赖兼容与数据库一致性。先断言默认配置，再用较短配置验证过期、续期；默认 5 秒用户冷却和默认 1 秒总量恢复各有实际验证。故障通过独占 Redis 的暂停/连接中断或受控不响应 TCP 测试验证，命令/连接失败必须在有限时间返回。

### 6. 维护、证据与清理

维护说明保存到 oj-friend/docs/submission-rate-limit.md。配置调整采用用户确认的停服流程：停止全部提交入口 → 统一改配置 → 仅枚举精确环境下的逻辑用户限流器和全局限流器 → 使用 RRateLimiter.delete() 删除关联状态 → 所有实例以一致配置启动。不得用不受限通配符、FLUSHDB/FLUSHALL 或普通请求中的 setRate 重置。普通重启不清状态。

运行证据必须先整合进保留报告，再清理临时日志/XML；报告保留命令、退出码、JUnit 原始 XML、关键日志、输入哈希和资源核对。正式 evidence manifest 引用保留报告，不留下指向已删除临时日志的证据路径。仅删除基线之外且确认独占的本轮 Docker 资源和工作目录，已有 64 个改动文件与本轮新增代码分别核对。计划来源保留于 .codex/plans，最终报告保留至用户明确要求删除。

## Risks / Trade-offs

- 新 Netty 版本可能与 Lettuce 或其他传递依赖冲突 → 仅 oj-friend 对齐，先检查依赖树，再做同 Redis 的真实兼容测试及既有提交/查询回归。
- 连续用户与总量检查会在总量拒绝时消耗用户许可 → 明确采用尝试计数，不增加补偿事务。
- Redis 不可用时新提交不可受理 → 使用有界命令等待并返回 503；不影响本轮范围之外的既有结果查询降级设计。
- 总量按周期允许短突发，且节点时钟、Redis 可用性会影响分布式限流 → 测试正常时钟环境下共享行为，不宣称抗所有故障或已有安全容量。
- 配置重启不会覆盖 Redis 已存规则 → 维护说明明确统一停服、限定清理和重新初始化，不做热更新。
- 必需原始证据与临时清理要求并存 → 将原始证据嵌入保留报告，指纹与映射绑定该报告；不先记录指纹再改报告。

## Migration Plan

没有数据库迁移。首发部署前完成 strict 独立评审及本轮测试，使用一致 environment 与规则配置部署全部提交入口；部署本身不在本轮授权内。回退业务版本须停止提交入口并按维护说明处理本功能状态，明确回退会移除本次入口保护；不自动回退真实环境。

## 实施与来源

来源为 proposal 指向的用户执行计划与当前实现。API 已核对 Redisson 3.52.0 的 RRateLimiter/Config/BaseConfig 及其 Netty BOM；Spring Boot 3.0.1 的有效连接由现有 Lettuce 工厂提供。机器证据方式为 machine（Python 3.9+、单根普通文件、Markdown checkbox）；strict 独立规划与代码评审分别进行，不用文件哈希代替语义评审。

当前 CLI 对根级自定义 workflow_profile 给出严格校验警告；本变更将同一标记登记于 .openspec.yaml 的 x-workflow.workflow_profile 扩展字段，以兼容 CLI。风险等级仍是 strict，证据助手继续传 --profile strict，评审门槛不变；不修改共享模板或其他变更的元数据。


## 2026-11-11 全局参数修订

按用户要求将 Java 默认值和 bootstrap 的 global-permits 同步调整为 3，global-interval 仍为 1s。用户限流、客户端配置、错误响应及池配置不变。复用原实现及独立评审，只对参数、配置绑定、并发许可和维护迁移预期做增量复核。真实 Redis 验证前三次通过、第四次拒绝、周期恢复及跨客户端共享三个许可；维护演练明确存量 2 次规则不会因新客户端参数为 3 而重置，停服限定清理后才使用新规则。保留既有报告中的 2 次测试数据。
