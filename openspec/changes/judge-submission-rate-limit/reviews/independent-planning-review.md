# 独立规划评审

- 变更：judge-submission-rate-limit
- 结论：PASS
- 风险等级：strict
- 核验方式：machine；由主执行者后续保存并核对正式规划指纹。
- 评审者：/root/submission_rate_review
- 评审时间：2026-11-10T22:02:45.878097+08:00
- 独立性：本执行上下文未撰写本次 proposal、spec、design、tasks 或业务实现；本次只读取规划、相关规则和参考代码，并写入本独立报告。
- 根目录：/Users/lllyw/lyw-oj

## 结论依据

用户批准的两层规则、提交尝试计数、真实 HTTP 错误、Redis 故障拒绝、停服统一调整及隔离测试清理，均已被规划承接。未发现需要改变已确认需求或阻止实施的规划缺口。PASS 仅表示当前规划可进入实施，不表示依赖集成、实际限流或故障恢复已经通过测试。

## 核对结果

| 检查面 | 评审结果与代码依据 | 已安排验证 |
|---|---|---|
| 范围及来源 | 仅增加判题提交入口的用户频率和总量频率；5 秒 1 次、总量每秒 2 次与用户计划一致，2 次/秒明确为试运行初值 | tasks 1.2、2.1、4.1 |
| 身份与入口共享 | 两个 Controller 入口分别调用 submit/rabbitSubmit，均在业务 try/catch 之前调用 prepareSubmission；该方法现有 currentUserId() 从鉴权 ThreadLocal 获取正 Long，规划在其后、assembleJudgeSubmitDTO 之前检查 | tasks 2.2；跨入口、跨题目、无效请求和未登录场景 |
| 顺序与副作用 | 先用户后全局；用户超限不触及全局；总量拒绝及后续失败保留已消耗的用户许可。拒绝位置在查题、提交 ID、等待缓存、RPC/MQ 之前，符合已确认的尝试计数语义 | tasks 2.1、2.2、4.1 |
| HTTP 契约 | 3602/3603/3604 在当前 ResultCode 中未占用。公共异常处理器现有返回 Result，规划明确只给新增码映射 429/429/503；该处理器已经由公共安全模块 AutoConfiguration.imports 注册 | tasks 2.3；真实 Servlet 状态与响应体、旧异常回归 |
| 客户端与依赖 | 当前已有 Spring Data Redis 和 Lettuce 缓存；普通 Redisson 依赖、独立 Bean、局部 Netty 对齐不要求替换现有连接工厂。读取生效单机配置、惰性连接、有限等待及关闭要求明确 | tasks 1.1、1.2、4.2、4.3 |
| 初始化及生命周期 | trySetRate 返回 false 按已有配置处理；普通请求不重置额度。用户四参数初始化含空闲期限，总量无过期；过期重建、活动续期、普通重启和不同环境隔离均有场景 | tasks 2.1、3.1、4.1 |
| 维护与清理 | 维护限于停服后的本环境逻辑限流器，使用对象删除清理关联数据；已有 Redis 业务键与其他环境键有保留验证。临时 XML/日志先并入保留报告，正式证据不指向删除路径 | tasks 3.2、4.4 |
| 验收与工作流 | 8 项 Requirement、24 个 Scenario 有相应实现和测试任务；11 项 checkbox 仍未勾选。独立代码评审和正式验收是交付条件，不构成先验收才能实施的循环 | tasks 交付条件；后续 strict 验收 |

## 阻塞项

无。

## 已处理的兼容事项

当前 CLI 要求自定义根级元数据采用 x- 扩展。本变更将风险标记保存为 x-workflow.workflow_profile: strict，design 明确记录了原因，后续证据命令仍使用 --profile strict，独立规划与代码评审以及真实 Redis 验收要求均保留。该处理没有降低风险级别，也没有要求修改共享模板或其他变更。

## 实施时必须落实的检查

这些检查已在当前任务中安排，不构成本次规划的未决要求：

1. Redisson 3.52.0 与 Netty 4.1.127.Final 的可运行性须由实际依赖树、JDK 17 构建和与 Lettuce/StateCacheService 共用隔离 Redis 的测试证实；本次静态评审不替代兼容性测试。
2. 连接复制应覆盖生效数据库、认证及 SSL 验证语义，不能只拼接 host/port 或忽略不支持的拓扑；惰性配置测试不得因默认配置误连业务 Redis。
3. 双客户端与并发用例须真实使用同名限流器，合理安排周期边界，分别证明默认 5 秒/1 秒行为和短周期 TTL 行为；连接中断与响应超时不得仅用 mock 当作真实故障证据。
4. 普通限流异常不得落入当前通用 ServiceException 的 error 堆栈日志；故障日志不输出可能包含凭据或源码的完整异常内容。
5. 生成验收指纹前完成报告中的原始证据整合及清理核对，之后再绑定最终保留报告；清理后核对引用路径仍存在。

## 已执行的只读检查

工作目录均为 /Users/lllyw/lyw-oj：

| 命令 | 退出码 | 结果 |
|---|---:|---|
| openspec status --change judge-submission-rate-limit --json | 0 | 四类规划产物齐备；此状态不代表实现完成 |
| openspec validate judge-submission-rate-limit --strict --no-interactive | 0 | Change 'judge-submission-rate-limit' is valid |

本阶段未运行构建、业务 HTTP、Docker、Redis、数据库或 RabbitMQ。未修改业务代码、规划正文或共享工作流规则；未代写聚合 change-review 或机器指纹。

## 文件基线

以下 SHA-256 由本次读取的普通文件计算；不是机器规划指纹或防篡改签名。参考代码允许在正式 start-apply 之后按任务修改，规划与来源变化则需要重新评审对应内容。

| 文件（相对项目根） | SHA-256 | 可执行 |
|---|---|---|
| `AGENTS.md` | `20357a5428a4639631d2cccf23c6b9eb47b615960acc1f30db1a77c7f4bb4409` | 否 |
| `.agents/skills/openspec-review-change/SKILL.md` | `5274cb3aeb7db1ff741925013940778134bc493aca77ed25b9b1c7c2d3aeba76` | 否 |
| `.agents/shared/openspec/workflow-policy.md` | `8a324454d91d27dda2fbf140198cbbc7413c3ca3fd9a1af75553b12517b60d1d` | 否 |
| `.agents/shared/openspec/review-checklist.md` | `f9b0b88624f17a916b52c027ec9490abc64484ad935b33dc24df36a33a76a3fa` | 否 |
| `.agents/shared/openspec/verification-evidence.md` | `6061d192eefbb4b0e8207b36d78a0ebb1aef7904f5d9caf6a5fbd52cb600cd19` | 否 |
| `.codex/plans/redisson-submit-rate-limit-20261110-215050-dc890d08.md` | `f4038949513159be4bdddc0e70d16ef0b53cd3ad5962b4ed6d4984c9dbf7fe76` | 否 |
| `openspec/changes/judge-submission-rate-limit/.openspec.yaml` | `5edc39ea7b0ff2246f3e22f3d27fdf240c5f12b6066c0097ddad27b3653d1ee4` | 否 |
| `openspec/changes/judge-submission-rate-limit/proposal.md` | `1fcc64d4e9cf4b69fe62ffe4455eb90469043a2f2b936ccb2e1ad0981ff50e17` | 否 |
| `openspec/changes/judge-submission-rate-limit/design.md` | `87ebdb8c4f89ea24ca27a588d20ac04eb33133fe7b567f25d924fb6046f6d352` | 否 |
| `openspec/changes/judge-submission-rate-limit/tasks.md` | `dda43e1b3fc7a482035ddd004d873455629e9ed1f58d0718a07b35e04624f17a` | 否 |
| `openspec/changes/judge-submission-rate-limit/specs/judge/submission-rate-limit/spec.md` | `47d770c069144ead5e3d03a9823c2e295cadfde4d6b32aa409ea0411e59335bf` | 否 |
| `pom.xml` | `2270275160c7f651a978323ed5ebc897e16875f1b50ba9cff0082e85ec02de89` | 否 |
| `oj-modules/oj-friend/pom.xml` | `577138b20c055ea9bfdbd3c701fff860c55c565548ec25b8720a643dc4386fb0` | 否 |
| `oj-commom/oj-common-redis/pom.xml` | `5d977ba6971697b156aa79f09e5afd055ec4565231ced65e1b3b3f275438c1d1` | 否 |
| `oj-modules/oj-friend/src/main/java/com/lyw/friend/OjFriendApplication.java` | `2fe080575e05524824f7e3581c28d84bb977af7f9c2758ba3c0a912330ad58c6` | 否 |
| `oj-modules/oj-friend/src/main/java/com/lyw/friend/controller/user/UserQuestionController.java` | `c103b5e251fe81bdb2787f5116d9def6af4f788fa0b8daaf31c1b9b9b128d2e0` | 否 |
| `oj-modules/oj-friend/src/main/java/com/lyw/friend/service/user/impl/UserQuestionServiceImpl.java` | `8bb4ecf733dcfe49941bef6bc1f8cc5b4d67ad261e23518cbc7a62a435b1e2f3` | 否 |
| `oj-commom/oj-common-core/src/main/java/com/lyw/common/core/enums/ResultCode.java` | `98e7400efceb232542bcd33fbe29a7778c6af0d5214c209d3e21c04da5202509` | 否 |
| `oj-commom/oj-common-security/src/main/java/com/lyw/common/security/handler/GlobalExceptionHandler.java` | `7157c3779cb3038a0516f86bc4939bfa85c93ba0f30379e8d87b11c029a57f66` | 否 |
| `oj-commom/oj-common-security/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | `9448a0b90e262388fa11b3f4845b48de1e1643c3514a1f71e4de214a9db74d18` | 否 |
| `oj-commom/oj-common-redis/src/main/java/com/lyw/common/redis/config/RedisConfig.java` | `1295bc86643cd6663a611171b2c6f68f3548126e3b655a0648465da097285169` | 否 |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/service/UserQuestionResultTest.java` | `0090a13268e744db804d55c64ec81163d12d3fd341f9a60a0a151d6ec783c32f` | 否 |

