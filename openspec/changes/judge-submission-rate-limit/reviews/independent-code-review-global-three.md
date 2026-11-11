# 全局默认每秒 3 次：独立增量代码评审

- 结论：PASS
- 变更：judge-submission-rate-limit
- 风险等级：strict；沿用 machine 核验。
- 评审者：/root/submission_rate_review
- 日期：2026-11-11；增量代码基线采集时间：2026-11-11T09:25:33.025771+08:00
- 独立性：本执行上下文未撰写本次六个文件修改或测试，只读差异、当前文件与最终运行证据，并写入本报告。
- 最新规划：independent-planning-review-global-three.md；check-plan --profile strict 返回 valid=true，记录的本轮 implementation_started=2026-11-11T01:21:49.353531+00:00。

## 结论与范围

六个文件的变更符合用户“全局默认提升到每秒 3 次”的要求。Java 与 bootstrap 均为 3/1s，用户默认仍为 5s；检查顺序、错误码、既有客户端和容器池行为未在本次增量中改变。未发现阻塞项。

本次复用旧 independent-code-review.md 中未变更部分的结论，并对其余 10 个文件逐一核对哈希未变。没有重新审计无关功能，也没有运行新的测试服务。旧独立报告和旧 2 次版本测量数据保持原样，旧报告 SHA-256 为 b72488e1c28e96e08e137b1051cf6076f630389e80a0b6656b1234df3ae1e99b。

## 增量核对结果

| 检查点 | 结果 |
|---|---|
| 默认值与配置 | SubmissionRateLimitProperties 的 globalPermits=3，bootstrap 的 global-permits=3；两个位置的周期仍为 1 秒，用户默认周期仍为 5 秒 |
| 配置绑定 | 默认值断言为 3；绑定样例改为显式覆盖 4 并断言 4，能够识别该字段未绑定的缺陷 |
| 初始化和固定快照 | 单元测试中的 OVERALL 初始化及配置快照断言同步为 3，用户许可、周期和 TTL 断言保留 |
| 默认周期行为 | defaultGlobalQuotaAllowsThreeThenRecoversAfterOneSecond 在同一周期验证前三次放行/第四次拒绝，1.1 秒后使用新的用户再次验证三个放行/第四个拒绝 |
| 跨客户端共享 | 16 用户在两个独立客户端之间合计 3 个通过、13 个总量拒绝，未变成每客户端独立三个许可 |
| 存量规则迁移 | 维护演练先建立额度 2，再用配置 3 的客户端检查，实际仍拒绝第三个且 getConfig 返回旧值 2；限定清理后新建规则返回 3，三个通过、第四个拒绝 |
| 维护边界 | 维护文档明确旧对象不被 trySetRate 覆盖，继续要求全入口停服、限定逻辑对象清理后统一启动；演练保留其他环境与业务键验证 |
| 其他参数 | 维护演练中的用户 2 秒是原有隔离样例的自定义配置，不是生产默认变化；不能对全部数字 2 作替换 |

## 实际测试与源码一致性

本评审读取了本轮完整 JUnit XML、构建记录及日志：

- 构建退出码：0。
- 开始：2026-11-11T09:22:25.983342+08:00；结束：2026-11-11T09:24:50.538235+08:00。
- JDK：17.0.15，Eclipse Adoptium，aarch64。
- JUnit 共发现 55 项，实际执行 53 项全部通过，失败/错误均为 0。
- 其中真实 Redis 14 项全部执行通过；原有两项 MySQL 集成按其独立开关跳过，未计为通过。
- 本轮日志记录“16 users across 2 clients, 3 allowed, 13 global-rejected”；同用户 20 次并发仍只有一次通过。
- 已确认 JUnit 包含新名称 defaultGlobalQuotaAllowsThreeThenRecoversAfterOneSecond 和更新后的维护演练用例。
- 所提供 business.diff 与六个当前文件逐文件一致。五个生产配置/测试文件与本轮 source 构建快照一致；维护文档不属于 Maven 编译输入，已单独检查当前内容和哈希。

稳定证据路径：`.codex/reports/redisson-global-three-20261111-091713-6359e1d3.md`。该文件已存在，主执行者还会追加清理结果，因此本报告不提前绑定其整体哈希。下列临时路径仅记录本次读取来源和原始字节哈希；正式验收应引用已整合原始日志/XML/命令/diff 的稳定报告，不将清理后的临时路径当作仍存在的证据。

## 剩余边界

本 PASS 是参数修改的独立代码质量结论，不代表已修改真实业务 Redis、部署或完成清理。已有业务 Redis 若存有 2 次规则，仍需在未来授权的部署维护中按说明切换。3 次/秒仅为试运行初值，本轮不是判题吞吐容量测试。正式证据整合、清理核对及最终机器验收由主执行者收尾。

## 本次六文件基线

| 文件（相对项目根） | 修改后 SHA-256 |
|---|---|
| `oj-modules/oj-friend/docs/submission-rate-limit.md` | `0077aab2eb4126e62b592c81651b5aaa0ef9570e8559e0ced1b7e7bc8e4a5d0c` |
| `oj-modules/oj-friend/src/main/java/com/lyw/friend/config/SubmissionRateLimitProperties.java` | `4710bc06c89cec8a463ce41fa697bd4158fc9a4521b0208a62cd23cbb41de2f8` |
| `oj-modules/oj-friend/src/main/resources/bootstrap.yml` | `497f3be25674a17bcc369c7011031efd48b5ae1034799cfa06ffcb28980c9d9d` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/config/SubmissionRateLimitConfigTest.java` | `cf23e19887823f80050fcfdb565302190ed67d4176c6176cc2992eaf65297a47` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/service/SubmissionRateLimiterRedisIntegrationTest.java` | `25e9540fe30322036dac96effab5696d7dccf76645f19f44c18333185c19f93d` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/service/SubmissionRateLimiterTest.java` | `e62ba9d480f1baf7039b96adb0da544c6a069ac362f13aacbb3f1ce579146db5` |

## 复用结论的未变更文件

| 文件（相对项目根） | SHA-256，与旧独立报告一致 |
|---|---|
| `oj-modules/oj-friend/pom.xml` | `e91c30002d518037ab6856caf63555cb9acfdf91efe9cea08bf5111bd1b200b6` |
| `oj-modules/oj-friend/src/main/java/com/lyw/friend/config/SubmissionRateLimitConfig.java` | `16441764ce1ababac7d1c2fa24850e7b5eee02039574f28ff55725d0b62606d1` |
| `oj-modules/oj-friend/src/main/java/com/lyw/friend/service/user/SubmissionRateLimiter.java` | `b8d9c400e27f253f946d72baf7e8ae3c2b5b84f10d77fad01f569ea61f19914c` |
| `oj-modules/oj-friend/src/main/java/com/lyw/friend/service/user/impl/UserQuestionServiceImpl.java` | `39cb25f964118ee03560b83ccc0a8e1471639c293e3d885687fb1a3f74249828` |
| `oj-commom/oj-common-core/src/main/java/com/lyw/common/core/enums/ResultCode.java` | `5475dac0de5b23d4d73fd810d0b9f4f6064c93e9f39d05029fa39ba4fe0476c7` |
| `oj-commom/oj-common-security/src/main/java/com/lyw/common/security/handler/GlobalExceptionHandler.java` | `6cccd02cfc76cf37e30bf011d7f79078abac470d82b4e2c152a5983ccf3c8945` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/service/SubmissionRateLimitBoundaryTest.java` | `79f1876df0a60a1fb4c59c25419ab1e2a7f5b3cc08f52c80e964f682405c32f7` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/service/RedisFaultProxy.java` | `b4309ee478a463e70a99d1abdb2475a929bdef8e14cfe7e4e3ce3b01bd968305` |
| `oj-modules/oj-friend/src/test/java/com/lyw/friend/service/UserQuestionResultTest.java` | `e6b7ff886f86cbe9ad3c46432bd5da31030b470feee34331cd0faa8782746811` |
| `oj-modules/oj-friend/src/test/resources/logback-test.xml` | `55dceb3b7199df4d4582ab10c59e11e1e782dc9790ae4f1615b1cd0ac9b53370` |

## 本轮原始证据基线

| 本次读取来源（将保存在稳定报告中） | SHA-256 |
|---|---|
| `.codex/redisson-global-three/20261111-091713-6359e1d3/evidence/build-result.json` | `826253ef320444f25761dfdfbe83c53670d9cc38ebe1aca336ad193160635c31` |
| `.codex/redisson-global-three/20261111-091713-6359e1d3/evidence/business.diff` | `ecce3df3b4409e0ea9970d323dca0f6840a2ea0fe86373562c79f0e74301a4f2` |
| `.codex/redisson-global-three/20261111-091713-6359e1d3/logs/build.log` | `2f112987fee2d4654a0859ffe21731fa6082308c2d4bb2665f8a7102f93938b6` |
| `.codex/redisson-global-three/20261111-091713-6359e1d3/source/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.config.SubmissionRateLimitConfigTest.xml` | `b8a49848cbffc7ba50feebf3037e288aeb0210858eed136f156795da24cb490e` |
| `.codex/redisson-global-three/20261111-091713-6359e1d3/source/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.JudgeProducerTest.xml` | `1f7bad455e82c2963494b0cdc703f7456b2d81979c25f27e0a95943d18c32849` |
| `.codex/redisson-global-three/20261111-091713-6359e1d3/source/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.QuestionReadBoundaryIntegrationTest.xml` | `b2218b164a63f5577a7ccecc63c7a556fad55df007fe711ad1d48f553bd18ecd` |
| `.codex/redisson-global-three/20261111-091713-6359e1d3/source/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.QuestionReadBoundaryTest.xml` | `c114fd9c4ff8c5d235daf9a01bc1c473537448a3e49012dc54d567ae77b5af51` |
| `.codex/redisson-global-three/20261111-091713-6359e1d3/source/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.SubmissionRateLimitBoundaryTest.xml` | `11aa3b6c79deca76db5e34d4077ea93862d5de3196eeda3cd62d6a14d90ac8bf` |
| `.codex/redisson-global-three/20261111-091713-6359e1d3/source/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.SubmissionRateLimiterRedisIntegrationTest.xml` | `5c55cdbffd8efff842045682c646f0f750bc86f13aec6d52cb07271123eea561` |
| `.codex/redisson-global-three/20261111-091713-6359e1d3/source/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.SubmissionRateLimiterTest.xml` | `803b53fe15703fb7c76a1c4badb1dfa2ead7145a288cbe58b34a4086774a11c1` |
| `.codex/redisson-global-three/20261111-091713-6359e1d3/source/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.UserQuestionLookupIntegrationTest.xml` | `bb8c205043c22a128532c29f3417a7b85ecc05df60f8160353c2220e26d9f8c4` |
| `.codex/redisson-global-three/20261111-091713-6359e1d3/source/oj-modules/oj-friend/target/surefire-reports/TEST-com.lyw.friend.service.UserQuestionResultTest.xml` | `9942aa3a66451bcc4c34c3f6088d6dbd1750073fcbd9c27d17f9ffb9b0bccaed` |

