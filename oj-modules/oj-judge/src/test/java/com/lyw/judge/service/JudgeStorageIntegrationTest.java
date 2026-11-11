package com.lyw.judge.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.lyw.api.domain.cache.JudgeResultCache;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.enums.JudgeResultState;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.common.redis.service.StateCacheService;
import com.lyw.judge.domain.SandBoxExecuteResult;
import com.lyw.judge.mapper.UserSubmitMapper;
import com.lyw.judge.service.impl.JudgeServiceImpl;
import com.lyw.judge.rabbit.JudgeConsumer;
import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 仅在本轮隔离 MySQL/Redis 容器中运行，不启动 Spring 应用或真实沙箱。 */
@EnabledIfSystemProperty(named = "oj.integration", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JudgeStorageIntegrationTest {
    private LettuceConnectionFactory redisFactory;
    private StateCacheService cache;
    private StringRedisTemplate redis;
    private JdbcTemplate jdbc;
    private UserSubmitStore store;
    private UserSubmitMapper mapper;
    // 每次运行使用独立 ID，保留大整数覆盖，避免重跑时命中上次测试的记录。
    private final AtomicLong ids = new AtomicLong(IdWorker.getId());

    @BeforeAll
    void prepare() throws Exception {
        String url = System.getProperty("oj.mysql.url", "jdbc:mysql://mysql:3306/oj_idempotency?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        assertTrue(url.contains("/oj_idempotency?"), "拒绝在非隔离测试库运行");
        DriverManagerDataSource source = new DriverManagerDataSource(url, "oj_test", "oj_test_password");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS tb_user_submit (
                  submit_id BIGINT UNSIGNED PRIMARY KEY, user_id BIGINT UNSIGNED NOT NULL,
                  question_id BIGINT UNSIGNED NOT NULL, exam_id BIGINT UNSIGNED NULL,
                  program_type TINYINT NOT NULL, user_code TEXT NOT NULL, pass TINYINT NOT NULL,
                  exe_message VARCHAR(500) NOT NULL, case_judge_res TEXT,
                  score INT NOT NULL DEFAULT 0, create_by BIGINT UNSIGNED NOT NULL,
                  create_time DATETIME NOT NULL, update_by BIGINT UNSIGNED, update_time DATETIME)
                """);
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(UserSubmitMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        GlobalConfig global = new GlobalConfig();
        global.setDbConfig(new GlobalConfig.DbConfig());
        factory.setGlobalConfig(global);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(UserSubmitMapper.class);
        store = new UserSubmitStore(mapper, new DataSourceTransactionManager(source));
        redisFactory = new LettuceConnectionFactory(System.getProperty("oj.redis.host", "redis"), 6379);
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        cache = new StateCacheService(redisFactory);
    }

    @AfterAll
    void shutdown() { if (redisFactory != null) redisFactory.destroy(); }

    @Test
    void realLuaPreservesPriorityLargeIdAndTtl() throws Exception {
        for (boolean trueFirst : new boolean[]{true, false}) {
            long id = ids.getAndIncrement();
            String key = CacheConstants.getJudgeResultKey(7L, id);
            var waiting = JudgeResultCache.waiting(7L, id);
            var error = JudgeResultCache.error(7L, id, "系统繁忙");
            var judged = JudgeResultCache.judged(7L, judged(id, 0));
            assertTrue(cache.update(key, waiting, 3, TimeUnit.SECONDS));
            if (trueFirst) {
                assertTrue(cache.update(key, judged, 3, TimeUnit.SECONDS));
                assertFalse(cache.update(key, error, 300, TimeUnit.SECONDS));
            } else {
                assertTrue(cache.update(key, error, 3, TimeUnit.SECONDS));
                assertFalse(cache.update(key, waiting, 300, TimeUnit.SECONDS));
                assertTrue(cache.update(key, judged, 3, TimeUnit.SECONDS));
            }
            long ttl = redis.getExpire(key, TimeUnit.MILLISECONDS);
            Thread.sleep(40);
            assertFalse(cache.update(key, judged, 300, TimeUnit.SECONDS));
            assertTrue(redis.getExpire(key, TimeUnit.MILLISECONDS) < ttl);
            var value = cache.get(key, JudgeResultCache.class).checkedResult(7L, id);
            assertEquals(id, value.getSubmitId());
            assertEquals(0, value.getPass());
            redis.delete(key);
        }
    }

    @Test
    void cacheServiceLoadsFromProductionAutoConfigurationMetadata() throws Exception {
        String resource = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
        URL location = StateCacheService.class.getProtectionDomain().getCodeSource().getLocation();
        URL imports = new URL(location.toString().endsWith(".jar")
                ? "jar:" + location + "!/" + resource : location + resource);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
             BufferedReader reader = new BufferedReader(new InputStreamReader(imports.openStream(), StandardCharsets.UTF_8))) {
            context.registerBean("connectionFactory", LettuceConnectionFactory.class,
                    () -> new LettuceConnectionFactory(System.getProperty("oj.redis.host", "redis"), 6379));
            for (String name : reader.lines().filter(line -> !line.isBlank() && !line.startsWith("#")).toList()) {
                context.register(Class.forName(name.trim()));
            }
            context.refresh();
            StateCacheService configured = context.getBean(StateCacheService.class);
            long id = ids.getAndIncrement();
            String key = CacheConstants.getJudgeResultKey(7L, id);
            assertTrue(configured.update(key, JudgeResultCache.waiting(7L, id), 1, TimeUnit.SECONDS));
            assertEquals("WAITING", configured.get(key, JudgeResultCache.class).getCacheState());
            redis.delete(key);
        }
    }

    @Test
    void concurrentLuaCannotDowngradeRealResult() throws Exception {
        long id = ids.getAndIncrement();
        String key = CacheConstants.getJudgeResultKey(7L, id);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> operations = List.of(
                    () -> cache.update(key, JudgeResultCache.error(7L, id, "错误"), 5, TimeUnit.SECONDS),
                    () -> cache.update(key, JudgeResultCache.judged(7L, judged(id, 1)), 5, TimeUnit.SECONDS),
                    () -> cache.update(key, JudgeResultCache.waiting(7L, id), 5, TimeUnit.SECONDS),
                    () -> cache.update(key, JudgeResultCache.error(7L, id, "迟到错误"), 5, TimeUnit.SECONDS));
            for (Future<Boolean> future : executor.invokeAll(operations)) future.get(5, TimeUnit.SECONDS);
            assertEquals(JudgeResultState.JUDGED, cache.get(key, JudgeResultCache.class).checkedResult(7L, id).getResultState());
        } finally {
            executor.shutdownNow();
            redis.delete(key);
        }
    }

    @Test
    void twoRealExecutionsCommitOneCanonicalRecord() throws Exception {
        JudgeSubmitDTO submission = submission();
        CountDownLatch entered = new CountDownLatch(2);
        ISandboxPoolService sandbox = mock(ISandboxPoolService.class);
        AtomicInteger executions = new AtomicInteger();
        when(sandbox.exeJavaCode(anyLong(), anyString(), anyList(), anyLong())).thenAnswer(call -> {
            int number = executions.incrementAndGet();
            entered.countDown();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            return success(number == 1 ? "3" : "4");
        });
        JudgeServiceImpl service = service(sandbox, cache);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<UserQuestionResultVO> one = executor.submit(() -> service.doJudgeJavaCode(submission));
            Future<UserQuestionResultVO> two = executor.submit(() -> service.doJudgeJavaCode(submission));
            UserQuestionResultVO first = one.get(10, TimeUnit.SECONDS);
            UserQuestionResultVO second = two.get(10, TimeUnit.SECONDS);
            assertEquals(first.getPass(), second.getPass());
            assertEquals(first.getExeMessage(), second.getExeMessage());
            assertEquals(1, count(submission.getSubmitId()));
            assertEquals(first.getPass(), mapper.selectById(submission.getSubmitId()).getPass());
            assertEquals(first.getPass(), cache.get(key(submission), JudgeResultCache.class).getResult().getPass());
            assertEquals(first.getPass(), service.doJudgeJavaCode(submission).getPass());
            assertEquals(2, executions.get(), "重复投递已有记录时不再次调用沙箱");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void earlySystemFailureThenRealResultIsNotADuplicateKeyFailure() {
        JudgeSubmitDTO submission = submission();
        ISandboxPoolService sandbox = mock(ISandboxPoolService.class);
        when(sandbox.exeJavaCode(anyLong(), anyString(), anyList(), anyLong()))
                .thenReturn(SandBoxExecuteResult.fail(CodeRunStatus.SYSTEM_BUSY), success("3"));
        JudgeServiceImpl service = service(sandbox, cache);
        assertThrows(JudgeSystemException.class, () -> service.doJudgeJavaCode(submission));
        assertEquals(0, count(submission.getSubmitId()));
        cache.update(key(submission), JudgeResultCache.error(7L, submission.getSubmitId(), "副本先失败"), 5, TimeUnit.MINUTES);
        assertEquals(1, service.doJudgeJavaCode(submission).getPass());
        assertEquals(1, count(submission.getSubmitId()));
        assertEquals("JUDGED", cache.get(key(submission), JudgeResultCache.class).getCacheState());
    }

    @Test
    void cacheFailureAfterCommitAndExpiryCanBeRecoveredWithoutJudgingAgain() throws Exception {
        JudgeSubmitDTO submission = submission();
        ISandboxPoolService sandbox = mock(ISandboxPoolService.class);
        when(sandbox.exeJavaCode(anyLong(), anyString(), anyList(), anyLong())).thenReturn(success("3"));
        StateCacheService unavailable = mock(StateCacheService.class);
        when(unavailable.update(anyString(), any(), anyLong(), any())).thenThrow(new IllegalStateException("缓存离线"));
        JudgeServiceImpl service = service(sandbox, unavailable);
        assertEquals(1, service.doJudgeJavaCode(submission).getPass());
        assertEquals(1, count(submission.getSubmitId()));
        ReflectionTestUtils.setField(service, "stateCacheService", cache);
        service.doJudgeJavaCode(submission);
        redis.expire(key(submission), Duration.ofMillis(30));
        Thread.sleep(60);
        assertNull(cache.get(key(submission), JudgeResultCache.class));
        service.doJudgeJavaCode(submission);
        assertEquals("JUDGED", cache.get(key(submission), JudgeResultCache.class).getCacheState());
        verify(sandbox).exeJavaCode(anyLong(), anyString(), anyList(), anyLong());
    }

    @Test
    void insertFailureRollsBackAndRetryMayJudgeAgain() {
        JudgeSubmitDTO submission = submission();
        ISandboxPoolService sandbox = mock(ISandboxPoolService.class);
        when(sandbox.exeJavaCode(anyLong(), anyString(), anyList(), anyLong())).thenReturn(success("3"));
        JudgeServiceImpl service = service(sandbox, cache);
        jdbc.execute("ALTER TABLE tb_user_submit ADD CONSTRAINT reject_owned_test CHECK (submit_id <> " + submission.getSubmitId() + ")");
        try {
            assertThrows(RuntimeException.class, () -> service.doJudgeJavaCode(submission));
            assertEquals(0, count(submission.getSubmitId()));
        } finally {
            jdbc.execute("ALTER TABLE tb_user_submit DROP CHECK reject_owned_test");
        }
        assertEquals(1, service.doJudgeJavaCode(submission).getPass());
        assertEquals(1, count(submission.getSubmitId()));
        verify(sandbox, times(2)).exeJavaCode(anyLong(), anyString(), anyList(), anyLong());
    }

    @Test
    void realBrokerRedeliveryAfterCommitBeforeAckReusesDatabase() throws Exception {
        JudgeSubmitDTO submission = submission();
        ISandboxPoolService sandbox = mock(ISandboxPoolService.class);
        when(sandbox.exeJavaCode(anyLong(), anyString(), anyList(), anyLong())).thenReturn(success("3"));
        JudgeServiceImpl service = service(sandbox, cache);
        com.rabbitmq.client.ConnectionFactory factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(System.getProperty("oj.rabbit.host", "rabbit"));
        factory.setUsername("oj_test");
        factory.setPassword("oj_test_password");
        String queue = "oj-idem-ack-" + UUID.randomUUID();
        try (var connection = factory.newConnection(); var monitor = connection.createChannel()) {
            monitor.queueDeclare(queue, false, false, false, null);
            try {
                var first = connection.createChannel();
                first.basicPublish("", queue, null, JSON.toJSONBytes(submission));
                var delivered = first.basicGet(queue, false);
                assertNotNull(delivered);
                service.doJudgeJavaCode(submission);
                first.close(); // 已提交数据库，但故意不 ACK。
                var retry = monitor.basicGet(queue, false);
                for (int i = 0; retry == null && i < 100; i++) {
                    Thread.sleep(20);
                    retry = monitor.basicGet(queue, false);
                }
                assertNotNull(retry);
                assertTrue(retry.getEnvelope().isRedeliver());
                JudgeConsumer consumer = new JudgeConsumer();
                ReflectionTestUtils.setField(consumer, "judgeService", service);
                consumer.consumeMsg(JSON.parseObject(retry.getBody(), JudgeSubmitDTO.class), monitor,
                        retry.getEnvelope().getDeliveryTag());
                assertEquals(1, count(submission.getSubmitId()));
                verify(sandbox).exeJavaCode(anyLong(), anyString(), anyList(), anyLong());
                assertEquals(0, monitor.queueDeclarePassive(queue).getMessageCount());
            } finally {
                monitor.queueDelete(queue);
            }
        }
    }

    private JudgeServiceImpl service(ISandboxPoolService sandbox, StateCacheService cacheService) {
        JudgeServiceImpl service = new JudgeServiceImpl();
        ReflectionTestUtils.setField(service, "userSubmitStore", store);
        ReflectionTestUtils.setField(service, "sandboxPoolService", sandbox);
        ReflectionTestUtils.setField(service, "stateCacheService", cacheService);
        return service;
    }

    private JudgeSubmitDTO submission() {
        JudgeSubmitDTO dto = JudgeServiceIdempotencyTest.submission();
        dto.setSubmitId(ids.getAndIncrement());
        return dto;
    }

    private int count(Long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tb_user_submit WHERE submit_id=?", Integer.class, id);
    }

    private String key(JudgeSubmitDTO dto) { return CacheConstants.getJudgeResultKey(dto.getUserId(), dto.getSubmitId()); }
    private SandBoxExecuteResult success(String output) { return SandBoxExecuteResult.success(CodeRunStatus.SUCCEED, List.of(output), 1L, 1L); }
    private UserQuestionResultVO judged(long id, int pass) {
        UserQuestionResultVO result = UserQuestionResultVO.noResult(id);
        result.setResultState(JudgeResultState.JUDGED);
        result.setPass(pass);
        result.setScore(pass * 10);
        result.setExeMessage(pass == 1 ? "运行成功" : "未通过");
        return result;
    }
}
