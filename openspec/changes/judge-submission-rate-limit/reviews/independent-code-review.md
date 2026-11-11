# 独立代码质量评审

- 变更：judge-submission-rate-limit
- 结论：PASS
- 风险等级：strict
- 评审者：/root/submission_rate_review
- 评审时间：2026-11-10T22:44:09.884446+08:00
- 独立性：本执行上下文未撰写本次业务实现、测试或维护文档。本阶段只读实现、规划记录与实际测试证据，并写入本报告。
- 基线：本报告下列 16 个代码/配置/测试/文档文件，以及最终成功轮次 build-04。
- 规划核对：machine，check-plan --profile strict 返回 valid=true；planning-review.json 记录 implementation_started=2026-11-10T14:05:50.640145+00:00，规划决议 PASS，独立规划报告已登记。

## 结论

未发现阻止本轮交付的代码缺陷。实现符合已批准的用户 5 秒 1 次、总量每秒 2 次、先用户后总量的提交尝试计数和 429/503 契约。最终 build-04 实际执行 53 项测试全部通过，其中 14 项真实 Redis；2 项原有 MySQL 集成测试按自身开关跳过，未将其计为通过。

本结论是独立代码质量评审，不替代主执行者仍需完成的证据整合、清理核对与最终机器验收。没有因测试数量而推导生产吞吐量。

## 实现核对

| 检查面 | 结果与依据 |
|---|---|
| 鉴权身份和两个入口 | prepareSubmission 先保留原有基础参数/语言校验，再从 currentUserId 获取正 Long 并调用同一 SubmissionRateLimiter。两个入口都在进入自身 RPC/MQ try/catch 前执行该方法。限流键不含题目或入口，客户端不能通过 DTO 指定限流用户。 |
| 受理副作用边界 | check 位于 assembleJudgeSubmitDTO、IdWorker、等待缓存和实际 RPC/MQ 之前。边界测试对用户/总量/Redis 故障验证所有下游无交互，放行后原有顺序和发送失败回执仍成立。 |
| 并发与扣减顺序 | 两层显式使用 OVERALL；trySetRate 的已有规则 false 不被当作拒绝；先用户 tryAcquire 成功后才访问总量对象；不调用 release/setRate。真实同用户 20 并发仅 1 个通过，16 用户/两个客户端合计 2 个通过。 |
| 错误分类和 HTTP | RedisException 单独映射 3604，业务拒绝保留 3602/3603。公共处理器只对新增枚举设置 429/429/503 并提前返回 Result.fail，避免进入旧的 error 堆栈分支。两个入口的状态、code/msg/data 均有 MockMvc 验证。 |
| 连接配置与惰性启动 | Config 由已有 Lettuce 工厂的生效单机配置构建，复制认证、DB、IPv6 地址及常规 SSL 验证设置；其余拓扑/不支持认证或 TLS 配置明确失败。单独命名的 Redisson Bean 不替换旧工厂，配置测试证明绑定后生效、无可用 Redis 时仍能创建惰性客户端、上下文关闭后客户端 shutdown。 |
| 固定版本适配 | Spring Data Redis 3.0 的通用构造器存在公开 standalone getter 不代表生效配置的情况；读取已固定版本的 configuration 字段有明确注释、字段/拓扑失败分支，以及通用构造器、旧构造器和其他拓扑测试。此兼容方式已记录在维护文档，未把它声明为任意版本通用。 |
| 生命周期 | 用户初始化使用空闲期限，总量不设置过期；检查失败与成功均不重置现有额度。真实 Redis 验证默认期限、拒绝活动续期、关联用户键共同过期、客户端重建后原额度继续有效，以及不同环境隔离。 |
| 维护删除 | 文档与演练均先收集符合精确用户正整数键/全局键的候选，再验证配置字段与 OVERALL，最后调用对象 delete 删除关联数据。其他环境和业务样例保留；遇到非限流候选在任何删除前失败。文档明确停全部入口及一致配置后重启，未新增管理 HTTP 接口。 |
| 日志与兼容 | 超限为类别级 debug，Redis 故障只记录异常类型；公共新增分支不打印堆栈。敏感错误消息和 Advice 日志测试通过。旧 Result 结构、旧错误行为、查询与消息发送回归通过。 |
| 依赖范围 | 仅 oj-friend 添加普通 Redisson 依赖及局部 Netty BOM。实际依赖树为 Redisson 3.52.0、Netty 核心组件 4.1.127.Final、Spring Data Redis 3.0.0、Lettuce 6.2.2.RELEASE。真实 ACL/DB5 中 Redisson、Lettuce、StateCacheService 同时工作。 |

## 阻塞项

无。

## 已知边界

- 反射适配依赖当前 Spring Data Redis 3.0 的内部配置结构；升级该依赖须重跑对应构造器、拓扑与实际连接测试。当前字段不可识别时明确失败，未静默回退 localhost。
- TLS 开关和常规 peer 验证通过配置单元测试核对；本轮实际 Redis 验证为非 TLS 的 ACL/DB5 连接，没有进行生产 TLS 握手。自定义 Lettuce SSL、StartTLS、动态认证、集群/哨兵/socket 被明确拒绝并记录限制。
- 身份测试检查现有鉴权 ThreadLocal 的使用与拒绝边界，不是整个网关 JWT 登录认证的端到端测试。数据库、真实 MQ、真实判题吞吐和跨主机时钟偏差不在本次验证范围内。
- 2 次/秒仍是配置初值，不代表服务器在慢题或高并发时具备对应安全容量。

## 最终测试证据

本评审读取主执行者产生的 build-04 记录，而未启动任何新服务或重跑测试。主执行者的最终命令退出码为 0，开始时间 2026-11-10T14:37:23.888504+00:00，结束时间 2026-11-10T14:38:32.245354+00:00。

```text
java -version && mvn -B -ntp -U -s /work/inputs/build-04-settings.xml -Dmaven.repo.local=/work/maven-cache -Djava.io.tmpdir=/work/tmp -pl :oj-friend -am -DfailIfNoTests=false -Doj.rate-limit.integration=true -Doj.rate-limit.host=redis-rate -Doj.rate-limit.port=6379 -Doj.rate-limit.environment=rateit-dc890d08-build-04 install dependency:tree '-Dincludes=org.redisson:*,io.netty:*,io.lettuce:*,org.springframework.data:spring-data-redis'
```

实际 JUnit 结果：

| 测试类 | 已执行 | 跳过 | 失败 | 错误 |
|---|---:|---:|---:|---:|
| `com.lyw.friend.config.SubmissionRateLimitConfigTest` | 9 | 0 | 0 | 0 |
| `com.lyw.friend.service.JudgeProducerTest` | 3 | 0 | 0 | 0 |
| `com.lyw.friend.service.QuestionReadBoundaryIntegrationTest` | 0 | 1 | 0 | 0 |
| `com.lyw.friend.service.QuestionReadBoundaryTest` | 1 | 0 | 0 | 0 |
| `com.lyw.friend.service.SubmissionRateLimitBoundaryTest` | 8 | 0 | 0 | 0 |
| `com.lyw.friend.service.SubmissionRateLimiterRedisIntegrationTest` | 14 | 0 | 0 | 0 |
| `com.lyw.friend.service.SubmissionRateLimiterTest` | 7 | 0 | 0 | 0 |
| `com.lyw.friend.service.UserQuestionLookupIntegrationTest` | 0 | 1 | 0 | 0 |
| `com.lyw.friend.service.UserQuestionResultTest` | 11 | 0 | 0 | 0 |
| 合计 | 53 | 2 | 0 | 0 |

真实证据标记包括：
- 同用户 20 次并发：1 次通过、19 次用户超限。
- 16 个用户跨两个客户端：2 次通过、14 次总量超限。
- Redis CLIENT PAUSE：HTTP 503，测得约 1515 ms，随后恢复。
- 转发到真实 Redis 的 TCP 连接断开：HTTP 503，测得约 37 ms，随后恢复。

独立核对 build-04 输入记录中 84 个 oj-friend/公共受影响输入，当前工作区及隔离快照均与记录一致；这包含实际执行的新增测试，未复用旧轮次测试证明新修改。

本阶段独立执行的只读核对：
- python3 .agents/shared/openspec/scripts/evidence.py check-plan --root . --change openspec/changes/judge-submission-rate-limit --profile strict：退出 0，valid=true。
- git diff --check -- oj-modules/oj-friend 及两个公共修改文件：退出 0。
- 读取并解析最终 JUnit、依赖树和源文件哈希，结果如上。

## 证据保留边界

最终稳定证据载体为 `.codex/reports/redisson-submit-rate-limit-20261110-215050-dc890d08.md`。主执行者须把下列实际读取的原始日志、JUnit 与输入记录无损整合到该报告，再清理本轮临时目录；正式验收索引应引用稳定报告。

下列临时路径仅说明本次评审读取来源和原始字节哈希，清理后不应作为仍存在的独立证据路径。报告还会补充清理结果，本独立报告不提前绑定其整体哈希；最终验收由主执行者绑定完成后的稳定报告。未经重新核对不得将本 PASS 移用于后续修改或其他构建轮次。

## 代码及文档基线

| 文件（相对项目根） | SHA-256 |
|---|---|
| `oj-modules/oj-friend/pom.xml` | `e91c30002d518037ab6856caf63555cb9acfdf91efe9cea08bf5111bd1b200b6` |
| `oj-modules/oj-friend/src/main/java/com/lyw/friend/config/SubmissionRateLimitProperties.java` | `e0199c8f882ab176ad30db67e183873de0a8e0ede25abc3fc1fdb3a908f8fa60` |
| `oj-modules/oj-friend/src/main/java/com/lyw/friend/config/SubmissionRateLimitConfig.java` | `16441764ce1ababac7d1c2fa24850e7b5eee02039574f28ff55725d0b62606d1` |
| `oj-modules/oj-friend/src/main/java/com/lyw/friend/service/user/SubmissionRateLimiter.java` | `b8d9c400e27f253f946d72baf7e8ae3c2b5b84f10d77fad01f569ea61f19914c` |
| `oj-modules/oj-friend/src/main/java/com/lyw/friend/service/user/impl/UserQuestionServiceImpl.java` | `39cb25f964118ee03560b83ccc0a8e1471639c293e3d885687fb1a3f74249828` |
| `oj-modules/oj-friend/src/main/resources/bootstrap.yml` | `2fe0174a2ef26cf463d23d01e41acd4b09c94f683d8d148a591b384a60411db1` |
| `oj-commom/oj-common-core/src/main/java/com/lyw/common/core/enums/ResultCode.java` | `5475dac0de5b23d4d73fd810d0b9f4f6064c93e9f39d05029fa39ba4fe0476c7` |
| `oj-commom/oj-common-security/src/main/java/com/lyw/common/security/handler/GlobalExceptionHandler.java` | `6cccd02cfc76cf37e30bf011d7f79078abac470d82b4e2c152a5983ccf3c8945` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/config/SubmissionRateLimitConfigTest.java` | `84dc5536ef35693200aecf5f3146fe5678283af22acf26f110ec0d550106948b` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/service/SubmissionRateLimiterTest.java` | `98f24f0e7e1e74a444971bce343d9bb4c293179dc09276ad40f1fd9b4889d65d` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/service/SubmissionRateLimitBoundaryTest.java` | `79f1876df0a60a1fb4c59c25419ab1e2a7f5b3cc08f52c80e964f682405c32f7` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/service/SubmissionRateLimiterRedisIntegrationTest.java` | `66b34ebba728fbf60090dc01b138598ffbb82fc2d29ae9c1cc265e7801729067` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/service/RedisFaultProxy.java` | `b4309ee478a463e70a99d1abdb2475a929bdef8e14cfe7e4e3ce3b01bd968305` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/service/UserQuestionResultTest.java` | `e6b7ff886f86cbe9ad3c46432bd5da31030b470feee34331cd0faa8782746811` |
| `oj-modules/oj-friend/src/test/resources/logback-test.xml` | `55dceb3b7199df4d4582ab10c59e11e1e782dc9790ae4f1615b1cd0ac9b53370` |
| `oj-modules/oj-friend/docs/submission-rate-limit.md` | `e8a8e60c5026ab3e0ecaaef46f359b43d62212efa5ac869850f24669851eaaaf` |

## 原始证据基线

| 本次读取路径（相对项目根；将整合到稳定报告） | SHA-256 |
|---|---|
| `.codex/redisson-submit-rate-limit/20261110-215050-dc890d08/logs/build-04.log` | `f7b915ac28e35c6e30991469213e62df62f81f311cb6f0dca5f878279d03ed98` |
| `.codex/redisson-submit-rate-limit/20261110-215050-dc890d08/inputs/build-04.json` | `9a94401f533f0f3e1f8288627c4d22303e6997599fe004ba431ac8425bee45af` |
| `.codex/redisson-submit-rate-limit/20261110-215050-dc890d08/build-04/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.config.SubmissionRateLimitConfigTest.xml` | `2a21864738e8979952fb31a61c335c36ec058577b7aebb48da1d6ddfcb034550` |
| `.codex/redisson-submit-rate-limit/20261110-215050-dc890d08/build-04/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.JudgeProducerTest.xml` | `50e315881e1df591ea7e22bbabf916f5e3309e3ce5ab6942ca141835b25046d9` |
| `.codex/redisson-submit-rate-limit/20261110-215050-dc890d08/build-04/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.QuestionReadBoundaryIntegrationTest.xml` | `1aa8c37b844bf75d3089ca2618c3e5d4448f79aff670d7b367a0addea6dceb1c` |
| `.codex/redisson-submit-rate-limit/20261110-215050-dc890d08/build-04/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.QuestionReadBoundaryTest.xml` | `9f93d2576b4859bff0163228d5d99c52ab52129e8553688a0f071cef6345e3c5` |
| `.codex/redisson-submit-rate-limit/20261110-215050-dc890d08/build-04/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.SubmissionRateLimitBoundaryTest.xml` | `f4fff49258819d40e1f8c0e1b74a8b80e83e002b21986dba170557f9b79e3878` |
| `.codex/redisson-submit-rate-limit/20261110-215050-dc890d08/build-04/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.SubmissionRateLimiterRedisIntegrationTest.xml` | `663e85415fd52521f8cbfd32471777dd6ee2acbe3a1be6556f1ce7efff0ee3e3` |
| `.codex/redisson-submit-rate-limit/20261110-215050-dc890d08/build-04/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.SubmissionRateLimiterTest.xml` | `ecd7244e61c9ea672b258048c6ea80910719bd88760eed918668bb938d4b91a4` |
| `.codex/redisson-submit-rate-limit/20261110-215050-dc890d08/build-04/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.UserQuestionLookupIntegrationTest.xml` | `62b865cda3f97faea68cac15dbfa160a3b829f922895e3bd4241aeebd23cf9b7` |
| `.codex/redisson-submit-rate-limit/20261110-215050-dc890d08/build-04/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.UserQuestionResultTest.xml` | `dd8c27c683568eb5606ef59dfb1b91ff24fbc24fd691a9a8a33970901b2c6ef7` |

