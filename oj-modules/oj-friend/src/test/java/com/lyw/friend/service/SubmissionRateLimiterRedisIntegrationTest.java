package com.lyw.friend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.api.domain.cache.JudgeResultCache;
import com.lyw.api.service.RemoteJudgeService;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.utils.ThreadLocalUtil;
import com.lyw.common.redis.service.StateCacheService;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.common.security.handler.GlobalExceptionHandler;
import com.lyw.friend.config.SubmissionRateLimitConfig;
import com.lyw.friend.config.SubmissionRateLimitProperties;
import com.lyw.friend.controller.user.UserQuestionController;
import com.lyw.friend.domain.user.dto.UserQuestionSubmitDTO;
import com.lyw.friend.mapper.question.QuestionMapper;
import com.lyw.friend.mapper.user.UserSubmitMapper;
import com.lyw.friend.rabbit.JudgeProducer;
import com.lyw.friend.service.user.SubmissionRateLimiter;
import com.lyw.friend.service.user.impl.UserQuestionServiceImpl;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 仅连接本轮 Docker 网络中的独占 Redis；不启动业务 Spring 应用或读取业务连接配置。 */
@EnabledIfSystemProperty(named = "oj.rate-limit.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(30)
class SubmissionRateLimiterRedisIntegrationTest {
    private static final String USERNAME = "rate-test-user";
    private static final String PASSWORD = "rate-test-only-password";
    private static final int DATABASE = 5;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Set<String> environments = new LinkedHashSet<>();
    private String host;
    private int port;
    private String environment;
    private LettuceConnectionFactory adminFactory;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private RedissonClient first;
    private RedissonClient second;
    private SubmissionRateLimitProperties properties;

    @BeforeAll
    void connectOnlyToTheExplicitIsolatedRedis() {
        host = System.getProperty("oj.rate-limit.host");
        String portProperty = System.getProperty("oj.rate-limit.port");
        environment = System.getProperty("oj.rate-limit.environment");
        assertEquals("redis-rate", host, "真实测试只允许独占 Docker 网络别名，禁止回退到业务 Redis");
        assertNotNull(portProperty, "必须显式传入测试 Redis 端口");
        port = Integer.parseInt(portProperty);
        assertEquals(6379, port);
        assertNotNull(environment, "必须显式传入本轮环境名称");
        assertTrue(environment.matches("rateit-[A-Za-z0-9_-]+"));
        adminFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), clientSettings());
        adminFactory.afterPropertiesSet();
        try (RedisConnection connection = adminFactory.getConnection()) {
            assertEquals("PONG", connection.ping());
            connection.execute("ACL", bytes("SETUSER"), bytes(USERNAME), bytes("on"),
                    bytes(">" + PASSWORD), bytes("~*"), bytes("+@all"));
        }
        factory = authenticatedFactory(host, port);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        first = newClient(factory);
        second = newClient(factory);
        // 预先建立真实连接，使并发断言不依赖首次 DNS/连接初始化的时延。
        assertFalse(first.getBucket("oj:" + environment + ":connection-check").isExists());
        assertFalse(second.getBucket("oj:" + environment + ":connection-check").isExists());
    }

    @BeforeEach
    void isolateEveryScenario() {
        properties = new SubmissionRateLimitProperties();
        properties.setEnvironment(environment + "-" + sequence.incrementAndGet());
        environments.add(properties.getEnvironment());
    }

    @AfterAll
    void closeAllClients() {
        try {
            if (first != null) first.shutdown(0, 2, TimeUnit.SECONDS);
        } finally {
            try {
                if (second != null) second.shutdown(0, 2, TimeUnit.SECONDS);
            } finally {
                if (factory != null) factory.destroy();
                if (adminFactory != null) adminFactory.destroy();
            }
        }
    }

    @Test
    void defaultRulesCreateAuthenticatedDatabaseScopedStateWithTheRightTtl() {
        limiter(first).check(7L);
        String userKey = properties.keyPrefix() + "user:7";
        RRateLimiter user = first.getRateLimiter(userKey);
        RRateLimiter global = first.getRateLimiter(properties.keyPrefix() + "global");
        assertEquals(RateType.OVERALL, user.getConfig().getRateType());
        assertEquals(1L, user.getConfig().getRate());
        assertEquals(5000L, user.getConfig().getRateInterval());
        assertTrue(user.remainTimeToLive() > 590_000);
        assertTrue(user.remainTimeToLive() <= 600_000);
        assertEquals(3L, global.getConfig().getRate());
        assertEquals(1000L, global.getConfig().getRateInterval());
        assertEquals(-1L, global.remainTimeToLive());
        assertEquals(Boolean.TRUE, redis.hasKey(userKey));
        try (RedisConnection connection = adminFactory.getConnection()) {
            assertEquals(Boolean.FALSE, connection.keyCommands().exists(bytes(userKey)));
        }
    }

    @Test
    void defaultFiveSecondUserCooldownRecoversAcrossClients() throws Exception {
        SubmissionRateLimiter one = limiter(first);
        SubmissionRateLimiter two = limiter(second);
        one.check(7L);
        assertCode(ResultCode.FAILED_SUBMIT_FREQUENT, () -> two.check(7L));
        Thread.sleep(5100);
        assertDoesNotThrow(() -> two.check(7L));
    }

    @Test
    void defaultGlobalQuotaAllowsThreeThenRecoversAfterOneSecond() throws Exception {
        SubmissionRateLimiter one = limiter(first);
        SubmissionRateLimiter two = limiter(second);
        one.check(1L);
        two.check(2L);
        one.check(3L);
        assertCode(ResultCode.FAILED_SUBMIT_BUSY, () -> two.check(4L));
        Thread.sleep(1100);
        assertDoesNotThrow(() -> two.check(5L));
        assertDoesNotThrow(() -> one.check(6L));
        assertDoesNotThrow(() -> two.check(7L));
        assertCode(ResultCode.FAILED_SUBMIT_BUSY, () -> one.check(8L));
    }

    @Test
    void sameUserConcurrentRequestsAcrossClientsHaveOnlyOneWinner() throws Exception {
        properties.setGlobalPermits(100);
        properties.setUserInterval(Duration.ofSeconds(20));
        List<ResultCode> outcomes = concurrent(20, false);
        assertEquals(1, outcomes.stream().filter(Objects::isNull).count());
        assertEquals(19, outcomes.stream().filter(ResultCode.FAILED_SUBMIT_FREQUENT::equals).count());
        System.out.println("RATE_REDIS_EVIDENCE same-user: 20 attempts, 1 allowed, 19 user-rejected");
    }

    @Test
    void differentUsersAcrossClientsShareOneGlobalQuota() throws Exception {
        properties.setGlobalInterval(Duration.ofSeconds(20));
        List<ResultCode> outcomes = concurrent(16, true);
        assertEquals(3, outcomes.stream().filter(Objects::isNull).count());
        assertEquals(13, outcomes.stream().filter(ResultCode.FAILED_SUBMIT_BUSY::equals).count());
        System.out.println("RATE_REDIS_EVIDENCE global: 16 users across 2 clients, 3 allowed, 13 global-rejected");
    }

    @Test
    void globalHttpRejectionKeepsTheUserCoolingAndHasNoAcceptanceSideEffects() throws Exception {
        properties.setGlobalPermits(1);
        properties.setGlobalInterval(Duration.ofSeconds(20));
        SubmissionRateLimiter limiter = limiter(first);
        limiter.check(1L);
        assertHttpFailure(limiter, 2L, ResultCode.FAILED_SUBMIT_BUSY);
        assertHttpFailure(limiter, 2L, ResultCode.FAILED_SUBMIT_FREQUENT);
    }

    @Test
    void deniedActivityRenewsIdleExpiryAndAllUserStateEventuallyExpires() throws Exception {
        properties.setUserInterval(Duration.ofMillis(1500));
        properties.setUserIdleTime(Duration.ofMillis(2000));
        properties.setGlobalPermits(100);
        SubmissionRateLimiter limiter = limiter(first);
        limiter.check(7L);
        String userKey = properties.keyPrefix() + "user:7";
        Set<String> userKeys = keysContaining(userKey);
        assertTrue(userKeys.size() >= 3, "应存在配置、许可值和使用记录");
        Thread.sleep(400);
        long before = first.getRateLimiter(userKey).remainTimeToLive();
        assertCode(ResultCode.FAILED_SUBMIT_FREQUENT, () -> limiter.check(7L));
        long after = first.getRateLimiter(userKey).remainTimeToLive();
        assertTrue(after > before + 200, "被拒绝的活动也应刷新空闲期限");
        await(Duration.ofSeconds(5), () -> !first.getRateLimiter(userKey).isExists());
        for (String key : userKeys) assertEquals(Boolean.FALSE, redis.hasKey(key), "残留用户状态：" + key);
        assertEquals(-1L, first.getRateLimiter(properties.keyPrefix() + "global").remainTimeToLive());
        assertDoesNotThrow(() -> limiter.check(7L));
    }

    @Test
    void rebuildingAClientDoesNotResetExistingUserOrGlobalState() {
        properties.setUserInterval(Duration.ofSeconds(20));
        properties.setGlobalPermits(1);
        properties.setGlobalInterval(Duration.ofSeconds(20));
        RedissonClient original = newClient(factory);
        try {
            new SubmissionRateLimiter(original, properties).check(7L);
        } finally {
            original.shutdown(0, 2, TimeUnit.SECONDS);
        }
        RedissonClient rebuilt = newClient(factory);
        try {
            SubmissionRateLimiter limiter = new SubmissionRateLimiter(rebuilt, properties);
            assertCode(ResultCode.FAILED_SUBMIT_FREQUENT, () -> limiter.check(7L));
            assertCode(ResultCode.FAILED_SUBMIT_BUSY, () -> limiter.check(8L));
        } finally {
            rebuilt.shutdown(0, 2, TimeUnit.SECONDS);
        }
    }

    @Test
    void otherEnvironmentsDoNotConsumeEachOthersUserQuota() {
        limiter(first).check(7L);
        SubmissionRateLimitProperties other = new SubmissionRateLimitProperties();
        other.setEnvironment(properties.getEnvironment() + "-other");
        environments.add(other.getEnvironment());
        assertDoesNotThrow(() -> new SubmissionRateLimiter(second, other).check(7L));
    }

    @Test
    void maintenanceResetsOnlyValidatedLogicalLimitersAndLetsNewRulesTakeEffect() {
        properties.setGlobalInterval(Duration.ofSeconds(20));
        properties.setGlobalPermits(2);
        limiter(first).check(1L);
        limiter(first).check(2L);
        // 新客户端的配置不能重置已存在的旧规则；停服维护后才能切换。
        properties.setGlobalPermits(3);
        assertCode(ResultCode.FAILED_SUBMIT_BUSY, () -> limiter(second).check(3L));
        assertEquals(2L, first.getRateLimiter(properties.keyPrefix() + "global").getConfig().getRate());
        Set<String> state = keysContaining(properties.keyPrefix());
        SubmissionRateLimitProperties other = new SubmissionRateLimitProperties();
        other.setEnvironment(properties.getEnvironment() + "-other");
        environments.add(other.getEnvironment());
        new SubmissionRateLimiter(second, other).check(7L);
        String unrelated = properties.keyPrefix() + "user:7:business";
        redis.opsForValue().set(unrelated, "保留的业务标记");
        resetOnlyLogicalLimiters(first, properties.keyPrefix());
        for (String key : state) assertEquals(Boolean.FALSE, redis.hasKey(key));
        assertEquals("保留的业务标记", redis.opsForValue().get(unrelated));
        assertTrue(first.getRateLimiter(other.keyPrefix() + "user:7").isExists());
        properties.setUserInterval(Duration.ofSeconds(2));
        SubmissionRateLimiter updated = limiter(first);
        updated.check(1L);
        updated.check(2L);
        updated.check(3L);
        assertEquals(2000L, first.getRateLimiter(properties.keyPrefix() + "user:1").getConfig().getRateInterval());
        assertEquals(3L, first.getRateLimiter(properties.keyPrefix() + "global").getConfig().getRate());
        assertCode(ResultCode.FAILED_SUBMIT_BUSY, () -> updated.check(4L));
    }

    @Test
    void maintenanceAbortsBeforeDeletionIfACandidateIsNotALimiter() {
        limiter(first).check(1L);
        String collision = properties.keyPrefix() + "user:999";
        redis.opsForValue().set(collision, "不是限流对象");
        assertThrows(RuntimeException.class, () -> resetOnlyLogicalLimiters(first, properties.keyPrefix()));
        assertTrue(first.getRateLimiter(properties.keyPrefix() + "user:1").isExists());
        assertTrue(first.getRateLimiter(properties.keyPrefix() + "global").isExists());
        assertEquals("不是限流对象", redis.opsForValue().get(collision));
    }

    @Test
    void existingLettuceAndStateCacheServiceWorkAlongsideRedisson() {
        String key = "oj:" + properties.getEnvironment() + ":judge-result:70001";
        StateCacheService cache = new StateCacheService(factory);
        assertTrue(cache.update(key, JudgeResultCache.waiting(7L, 70001L), 1, TimeUnit.MINUTES));
        JudgeResultCache value = cache.get(key, JudgeResultCache.class);
        assertNotNull(value);
        assertEquals(70001L, value.checkedResult(7L, 70001L).getSubmitId());
        limiter(first).check(7L);
        assertNotNull(redis.opsForValue().get(key));
        assertNotNull(cache.get(key, JudgeResultCache.class));
    }

    @Test
    void realRedisResponsePauseProducesBoundedHttp503ThenRecovers() throws Exception {
        SubmissionRateLimiter limiter = limiter(first);
        limiter.check(1L);
        try {
            try (RedisConnection connection = adminFactory.getConnection()) {
                connection.execute("CLIENT", bytes("PAUSE"), bytes("2500"), bytes("ALL"));
            }
            long started = System.nanoTime();
            assertHttpFailure(limiter, 2L, ResultCode.FAILED_SUBMIT_LIMIT_UNAVAILABLE);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsed < 5000, "命令超时应有限返回");
            System.out.println("RATE_REDIS_EVIDENCE paused-server: HTTP 503 after " + elapsed + " ms");
        } finally {
            await(Duration.ofSeconds(8), this::adminPing);
        }
        AtomicLong user = new AtomicLong(100);
        await(Duration.ofSeconds(8), () -> {
            limiter.check(user.incrementAndGet());
            return true;
        });
    }

    @Test
    void disconnectingARealRedisForwardingConnectionProducesHttp503ThenRecovers() throws Exception {
        try (RedisFaultProxy proxy = new RedisFaultProxy(host, port)) {
            RedissonClient client = newClient(authenticatedFactory("127.0.0.1", proxy.port()));
            try {
                SubmissionRateLimiter limiter = new SubmissionRateLimiter(client, properties);
                limiter.check(1L);
                proxy.disconnect();
                long started = System.nanoTime();
                assertHttpFailure(limiter, 2L, ResultCode.FAILED_SUBMIT_LIMIT_UNAVAILABLE);
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue(elapsed < 5000, "连接故障应有限返回");
                System.out.println("RATE_REDIS_EVIDENCE disconnected-proxy: HTTP 503 after " + elapsed + " ms");
                proxy.reconnect();
                AtomicLong user = new AtomicLong(100);
                await(Duration.ofSeconds(8), () -> {
                    limiter.check(user.incrementAndGet());
                    return true;
                });
            } finally {
                client.shutdown(0, 2, TimeUnit.SECONDS);
            }
        }
    }

    private List<ResultCode> concurrent(int count, boolean differentUsers) throws Exception {
        SubmissionRateLimiter one = limiter(first);
        SubmissionRateLimiter two = limiter(second);
        ExecutorService threads = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResultCode>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                final int current = index;
                futures.add(threads.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("并发测试未启动");
                    try {
                        (current % 2 == 0 ? one : two).check(differentUsers ? current + 1L : 7L);
                        return null;
                    } catch (ServiceException failure) {
                        return failure.getResultCode();
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            List<ResultCode> outcomes = new ArrayList<>();
            for (Future<ResultCode> future : futures) outcomes.add(future.get(10, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            start.countDown();
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    /** 与维护文档一致：先收集并验证全部逻辑配置键，再删除对象及其关联状态。 */
    static void resetOnlyLogicalLimiters(RedissonClient client, String prefix) {
        Pattern user = Pattern.compile(Pattern.quote(prefix + "user:") + "[1-9][0-9]*");
        List<String> logical = new ArrayList<>();
        client.getKeys().getKeysByPattern(prefix + "user:*").forEach(name -> {
            if (user.matcher(name).matches()) logical.add(name);
        });
        if (client.getRateLimiter(prefix + "global").isExists()) logical.add(prefix + "global");
        for (String name : logical) {
            Map<String, String> config = client.<String, String>getMap(name, StringCodec.INSTANCE).readAllMap();
            if (!config.keySet().containsAll(Set.of("rate", "interval", "type", "keepAliveTime"))) {
                throw new IllegalStateException("候选键不是完整限流配置，停止维护：" + name);
            }
            if (client.getRateLimiter(name).getConfig().getRateType() != RateType.OVERALL) {
                throw new IllegalStateException("候选键不是本功能的共享限流规则，停止维护：" + name);
            }
        }
        logical.forEach(name -> client.getRateLimiter(name).delete());
    }

    private Set<String> keysContaining(String fragment) {
        return first.getKeys().getKeysStream().filter(key -> key.contains(fragment)).collect(Collectors.toSet());
    }

    private SubmissionRateLimiter limiter(RedissonClient client) {
        return new SubmissionRateLimiter(client, properties);
    }

    private boolean adminPing() {
        try (RedisConnection connection = adminFactory.getConnection()) {
            return "PONG".equals(connection.ping());
        }
    }

    private void assertHttpFailure(SubmissionRateLimiter limiter, long userId, ResultCode expected) throws Exception {
        UserQuestionServiceImpl service = new UserQuestionServiceImpl();
        QuestionMapper questions = mock(QuestionMapper.class);
        UserSubmitMapper submissions = mock(UserSubmitMapper.class);
        StateCacheService cache = mock(StateCacheService.class);
        JudgeProducer producer = mock(JudgeProducer.class);
        RemoteJudgeService judge = mock(RemoteJudgeService.class);
        ReflectionTestUtils.setField(service, "submissionRateLimiter", limiter);
        ReflectionTestUtils.setField(service, "questionMapper", questions);
        ReflectionTestUtils.setField(service, "userSubmitMapper", submissions);
        ReflectionTestUtils.setField(service, "stateCacheService", cache);
        ReflectionTestUtils.setField(service, "judgeProducer", producer);
        ReflectionTestUtils.setField(service, "remoteJudgeService", judge);
        UserQuestionController controller = new UserQuestionController();
        ReflectionTestUtils.setField(controller, "userQuestionService", service);
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
        UserQuestionSubmitDTO request = new UserQuestionSubmitDTO();
        request.setQuestionId(9L);
        request.setProgramType(0);
        request.setUserCode("public class Solution {}");
        ObjectMapper mapper = new ObjectMapper();
        ThreadLocalUtil.set(Constants.USER_ID, userId);
        try {
            var response = mvc.perform(post("/user/question/rabbit/submit")
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(request))).andReturn().getResponse();
            assertEquals(expected == ResultCode.FAILED_SUBMIT_LIMIT_UNAVAILABLE ? 503 : 429, response.getStatus());
            JsonNode body = mapper.readTree(response.getContentAsString(StandardCharsets.UTF_8));
            assertEquals(expected.getCode(), body.path("code").asInt());
            assertEquals(expected.getMsg(), body.path("msg").asText());
            assertTrue(body.get("data").isNull());
            verifyNoInteractions(questions, submissions, cache, producer, judge);
        } finally {
            ThreadLocalUtil.remove();
        }
    }

    private static LettuceClientConfiguration clientSettings() {
        return LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).build();
    }

    private static LettuceConnectionFactory authenticatedFactory(String host, int port) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(host, port);
        standalone.setDatabase(DATABASE);
        standalone.setUsername(USERNAME);
        standalone.setPassword(PASSWORD);
        return new LettuceConnectionFactory(standalone, clientSettings());
    }

    private static RedissonClient newClient(LettuceConnectionFactory factory) {
        return Redisson.create(SubmissionRateLimitConfig.clientConfig(factory));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static void assertCode(ResultCode expected, Runnable action) {
        assertEquals(expected, assertThrows(ServiceException.class, action::run).getResultCode());
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException last = null;
        do {
            try {
                if (condition.getAsBoolean()) return;
            } catch (RuntimeException failure) {
                last = failure;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("在期限内未观察到预期恢复或过期", last);
    }
}

