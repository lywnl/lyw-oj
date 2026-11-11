# 提交限流配置与维护

普通提交和 MQ 提交在 prepareSubmission 中共用 SubmissionRateLimiter。基础参数和鉴权检查通过后，先消耗用户许可，再检查全部用户共享的总量许可；通过后才查询题目和创建提交。总量拒绝及后续失败不会退还已经消耗的用户许可。

## 配置

配置前缀为 judge.submit-limit：

| 配置 | 默认值 | 说明 |
|---|---|---|
| environment | 当前 spring.profiles.active；未配置时 dev | 同一环境的所有实例必须一致；允许 1～64 位字母、数字、下划线和连字符，并以字母或数字开头 |
| user-interval | 5s | 一个用户每周期 1 个许可，跨题目和两个入口共享 |
| user-idle-time | 10m | 用户状态的空闲期限，必须不小于 user-interval |
| global-permits | 3 | 同环境所有实例合计的每周期额度 |
| global-interval | 1s | 总量额度周期 |

示例名称为 oj:prod:submit:user:123 和 oj:prod:submit:global；两层都使用 RateType.OVERALL。该前缀专用于本功能，不在此名称空间存放其他业务数据。用户状态随检查活动续期，全局状态不过期。总量可以短时同时放行三个请求；3 次/秒是试运行初值，尚不是服务器容量结论。

客户端为独立的 submissionRedissonClient Bean，使用 Redisson 3.52.0 与 oj-friend 局部 Netty 4.1.127.Final。原有 RedisTemplate/StateCacheService 继续使用 Lettuce。新客户端复用现有单实例连接的地址、数据库、用户名、密码以及 SSL 开关/验证设置；连接和命令超时各 1 秒，命令重试 0 次，连接池上限 8、最小空闲 1。

Spring Data Redis 3.0 的通用构造器可能让公开 standalone getter 返回默认值，因此适配器读取固定版本工厂的生效配置；无法识别时失败，不回退连接 localhost。集群、哨兵、Unix socket、StartTLS、动态认证和自定义 Lettuce SSL 选项不属于本次接入范围，会明确报配置错误。自定义证书或 SSL 上下文不得静默替换为默认信任配置。升级 Spring Data Redis 或增加这些连接能力时，需要重新验证适配器。

## 调用与错误

使用立即尝试的 tryAcquire()；没有许可时直接拒绝，不等待令牌。trySetRate() 返回 false 只表示规则已存在，不表示超限。

| 原因 | HTTP | code | msg |
|---|---|---|---|
| 用户超限 | 429 | 3602 | 提交过于频繁，请稍后再试 |
| 总量超限 | 429 | 3603 | 当前提交请求较多，请稍后再试 |
| 限流 Redis 连接或命令失败 | 503 | 3604 | 提交服务暂时不可用，请稍后再试 |

响应仍包含 code、msg、data，以上拒绝的 data 为 null，不返回提交 ID。调用方应停止本次提交，不据此启动结果轮询。限流规则记录的是提交尝试，后续题目校验、发送失败或判题失败都不会退还许可。其他已有错误码沿用原处理方式。

客户端使用惰性连接和惰性规则初始化。Redis 暂不可用时，新增限流组件不在配置阶段主动建立连接；检查阶段失败会返回 503。日志只记录原因类别，普通超限无异常堆栈，故障不拼接第三方异常中的认证信息或用户代码。

## 停服统一调整

普通重启不会覆盖 Redis 内已经存在的规则，单纯修改配置再重启不能保证存量规则更新。首版不提供热更新或管理 HTTP 接口。

默认总量已从每秒 2 次调整为每秒 3 次。若 Redis 中已有每秒 2 次的规则，新客户端调用 trySetRate() 仍保留旧规则，必须按下列停服流程切换；用户默认冷却保持 5 秒。回退总量参数也按同一流程处理，不在正常请求中重置额度。

1. 停止同一环境的全部提交入口，确认没有仍在进行额度检查的请求；现有判题任务按原流程处理。
2. 准备所有实例一致的新配置，确认环境前缀和 Redis 地址、数据库。额度与周期必须为正，用户空闲期限不得小于用户周期。
3. 用同一连接配置创建受控维护客户端，收集本环境的逻辑用户限流器与全局限流器；先验证全部候选，再通过 RRateLimiter.delete() 删除关联状态。
4. 启动使用一致配置的全部入口。首次合格请求会重新初始化规则；验证默认/新规则及错误响应后恢复受理。

下面的 Java 片段表达第 3 步；client 是受控维护客户端，prefix 是已经验证的旧环境前缀，例如 oj:prod:submit:。仅在全部入口停止后执行。若发现候选类型或字段异常，应停止核对，不能扩大匹配范围或改用全库清理。

```java
Pattern user = Pattern.compile(Pattern.quote(prefix + "user:") + "[1-9][0-9]*");
List<String> logical = new ArrayList<>();
client.getKeys().getKeysByPattern(prefix + "user:*").forEach(name -> {
    if (user.matcher(name).matches()) logical.add(name);
});
if (client.getRateLimiter(prefix + "global").isExists()) {
    logical.add(prefix + "global");
}
for (String name : logical) {
    Map<String, String> config =
            client.<String, String>getMap(name, StringCodec.INSTANCE).readAllMap();
    if (!config.keySet().containsAll(Set.of("rate", "interval", "type", "keepAliveTime"))) {
        throw new IllegalStateException("候选键不是完整限流配置，停止维护：" + name);
    }
    if (client.getRateLimiter(name).getConfig().getRateType() != RateType.OVERALL) {
        throw new IllegalStateException("候选键不是本功能的共享限流规则，停止维护：" + name);
    }
}
logical.forEach(name -> client.getRateLimiter(name).delete());
```

通过对象删除，是为了同时清除 Redisson 的配置键、许可值和使用记录。不要只删除裸配置键，也不要使用 FLUSHDB、FLUSHALL 或未限定环境的批量删除。客户端在维护完成后关闭；回退版本同样先停入口，明确回退会移除本次限流保护。

## 验证入口

JUnit、Mockito 和 MockMvc 验证配置、检查顺序、受理副作用边界和 HTTP 契约。SubmissionRateLimiterRedisIntegrationTest 使用独立的 oj.rate-limit.integration=true 开关，必须明确提供：

- oj.rate-limit.host=redis-rate：仅允许本轮独占 Docker 网络别名。
- oj.rate-limit.port=6379：该独占容器的内部端口，不映射业务 Redis。
- oj.rate-limit.environment=rateit-<本轮唯一标识>：用于隔离各次测试。

在 JDK 17 下运行 Maven 的 oj-friend 及其依赖测试。测试使用独占 redis:7.2-alpine；真实连接到 Redis，用两个 Redisson 客户端验证共享额度，用真实 TCP 转发断连及 Redis CLIENT PAUSE 验证失败和恢复，并与 Lettuce/StateCacheService 共用认证数据库。原有 MySQL 集成测试使用其自己的开关，不由本轮 Redis 开关启用。

测试维护片段覆盖其他环境/业务键保留及异常候选时先验证后删除。空闲 TTL 采用默认值断言和缩短测试周期验证；用户默认 5 秒冷却与全局默认 1 秒恢复另有实际测试。

本轮测试只验证受理频率和故障行为，不运行真实题目吞吐压测，也不宣称端到端生产环境容量。

