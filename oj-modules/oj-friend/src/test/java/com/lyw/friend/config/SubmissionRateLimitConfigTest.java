package com.lyw.friend.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SslOptions;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.redisson.config.SslVerificationMode;
import org.redisson.config.TransportMode;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisSocketConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.RedisStaticMasterReplicaConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class SubmissionRateLimitConfigTest {
    @Test
    void defaultsAndEnvironmentNamesMatchTheApprovedRules() {
        SubmissionRateLimitProperties properties = new SubmissionRateLimitProperties();
        properties.validate();
        assertEquals(Duration.ofSeconds(5), properties.getUserInterval());
        assertEquals(Duration.ofMinutes(10), properties.getUserIdleTime());
        assertEquals(3, properties.getGlobalPermits());
        assertEquals(Duration.ofSeconds(1), properties.getGlobalInterval());
        assertEquals("oj:dev:submit:", properties.keyPrefix());
        properties.setEnvironment("prod");
        assertEquals("oj:prod:submit:", properties.keyPrefix());
    }

    @Test
    void rejectsInvalidRulesBeforeAClientCanUseThem() {
        for (Duration invalid : List.of(Duration.ZERO, Duration.ofMillis(-1), Duration.ofNanos(1), Duration.ofSeconds(Long.MAX_VALUE))) {
            SubmissionRateLimitProperties user = new SubmissionRateLimitProperties();
            user.setUserInterval(invalid);
            assertThrows(IllegalArgumentException.class, user::validate);
            SubmissionRateLimitProperties global = new SubmissionRateLimitProperties();
            global.setGlobalInterval(invalid);
            assertThrows(IllegalArgumentException.class, global::validate);
        }
        SubmissionRateLimitProperties properties = new SubmissionRateLimitProperties();
        properties.setUserIdleTime(Duration.ofSeconds(4));
        assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setUserIdleTime(Duration.ofSeconds(5));
        assertDoesNotThrow(properties::validate);
        properties.setGlobalPermits(0);
        assertThrows(IllegalArgumentException.class, properties::validate);
        properties.setGlobalPermits(2);
        properties.setUserInterval(null);
        assertThrows(IllegalArgumentException.class, properties::validate);
    }

    @Test
    void rejectsEnvironmentNamesThatCouldBroadenMaintenanceMatching() {
        for (String environment : new String[]{null, "", "prod:*", "prod?", "prod{other}", "prod,local", "../prod", "a".repeat(65)}) {
            SubmissionRateLimitProperties properties = new SubmissionRateLimitProperties();
            properties.setEnvironment(environment);
            assertThrows(IllegalArgumentException.class, properties::validate);
        }
    }

    @Test
    void copiesTheEffectiveGenericStandaloneConfigurationAndSslVerification() {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration("::1", 6381);
        standalone.setDatabase(7);
        standalone.setUsername("rate-test-user");
        standalone.setPassword(RedisPassword.of("rate-test-only-password"));
        LettuceClientConfiguration ssl = LettuceClientConfiguration.builder().useSsl().disablePeerVerification().and().build();
        // 通用构造器不会填充旧公开 standalone getter；必须读取真正的配置。
        LettuceConnectionFactory factory = new LettuceConnectionFactory((RedisConfiguration) standalone, ssl);
        Config config = SubmissionRateLimitConfig.clientConfig(factory);
        SingleServerConfig single = config.useSingleServer();
        assertEquals("rediss://[::1]:6381", single.getAddress());
        assertEquals(7, single.getDatabase());
        assertEquals("rate-test-user", single.getUsername());
        assertEquals("rate-test-only-password", single.getPassword());
        assertEquals(SslVerificationMode.NONE, single.getSslVerificationMode());
        assertTrue(config.isLazyInitialization());
        assertEquals(TransportMode.NIO, config.getTransportMode());
        assertEquals(1000, single.getConnectTimeout());
        assertEquals(1000, single.getTimeout());
        assertEquals(0, single.getRetryAttempts());
        assertEquals(8, single.getConnectionPoolSize());
        assertEquals(1, single.getConnectionMinimumIdleSize());
        assertEquals("rate-test-only-password", new String(standalone.getPassword().get()));
    }

    @Test
    void preservesVerifiedSslAndLegacyStandaloneSettings() {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration("redis.test.invalid", 6380);
        LettuceClientConfiguration ssl = LettuceClientConfiguration.builder().useSsl().and().build();
        Config config = SubmissionRateLimitConfig.clientConfig(new LettuceConnectionFactory(standalone, ssl));
        assertEquals("rediss://redis.test.invalid:6380", config.useSingleServer().getAddress());
        assertEquals(SslVerificationMode.STRICT, config.useSingleServer().getSslVerificationMode());
        Config plain = SubmissionRateLimitConfig.clientConfig(new LettuceConnectionFactory("127.0.0.1", 6378));
        assertEquals("redis://127.0.0.1:6378", plain.useSingleServer().getAddress());
        assertNull(plain.useSingleServer().getPassword());
    }

    @Test
    void rejectsOtherTopologiesInsteadOfFallingBackToLocalhost() {
        List<RedisConfiguration> configurations = List.of(
                new RedisClusterConfiguration(List.of("redis.invalid:6379")),
                new RedisSentinelConfiguration("master", java.util.Set.of("redis.invalid:26379")),
                new RedisStaticMasterReplicaConfiguration("redis.invalid", 6379),
                new RedisSocketConfiguration("/unused-test-redis.sock"));
        for (RedisConfiguration configuration : configurations) {
            LettuceConnectionFactory factory = new LettuceConnectionFactory(configuration, LettuceClientConfiguration.defaultConfiguration());
            assertThrows(IllegalArgumentException.class, () -> SubmissionRateLimitConfig.clientConfig(factory));
        }
        assertThrows(IllegalArgumentException.class,
                () -> SubmissionRateLimitConfig.clientConfig(mock(RedisConnectionFactory.class)));
        LettuceClientConfiguration startTls = LettuceClientConfiguration.builder().useSsl().startTls().and().build();
        assertThrows(IllegalArgumentException.class, () -> SubmissionRateLimitConfig.clientConfig(
                new LettuceConnectionFactory(new RedisStandaloneConfiguration("redis.invalid", 6379), startTls)));
    }

    @Test
    void customSslOptionsAreRejectedInsteadOfSilentlyLosingTheirTrustSettings() {
        LettuceClientConfiguration ssl = LettuceClientConfiguration.builder()
                .clientOptions(ClientOptions.builder().sslOptions(SslOptions.create()).build())
                .useSsl().and().build();
        assertThrows(IllegalArgumentException.class, () -> SubmissionRateLimitConfig.clientConfig(
                new LettuceConnectionFactory(new RedisStandaloneConfiguration("redis.invalid", 6380), ssl)));
        LettuceClientConfiguration defaults = LettuceClientConfiguration.builder()
                .clientOptions(ClientOptions.create()).useSsl().and().build();
        assertEquals(SslVerificationMode.STRICT, SubmissionRateLimitConfig.clientConfig(
                new LettuceConnectionFactory(new RedisStandaloneConfiguration("redis.invalid", 6380), defaults))
                .useSingleServer().getSslVerificationMode());
    }

    @Test
    void springBindsTheConfiguredRulePrefixBeforeValidation() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                    "rate-test", java.util.Map.of(
                    "judge.submit-limit.environment", "qa",
                    "judge.submit-limit.user-interval", "7s",
                    "judge.submit-limit.user-idle-time", "2m",
                    "judge.submit-limit.global-permits", "4",
                    "judge.submit-limit.global-interval", "2s")));
            context.registerBean(RedisConnectionFactory.class, () -> new LettuceConnectionFactory("127.0.0.1", 1));
            context.register(SubmissionRateLimitConfig.class);
            context.refresh();
            SubmissionRateLimitProperties properties = context.getBean(SubmissionRateLimitProperties.class);
            assertEquals("qa", properties.getEnvironment());
            assertEquals(Duration.ofSeconds(7), properties.getUserInterval());
            assertEquals(Duration.ofMinutes(2), properties.getUserIdleTime());
            assertEquals(4, properties.getGlobalPermits());
            assertEquals(Duration.ofSeconds(2), properties.getGlobalInterval());
        }
    }

    @Test
    void springCreatesTheDedicatedLazyClientWithoutAReachableRedisAndClosesIt() {
        RedissonClient client;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(RedisConnectionFactory.class, () -> new LettuceConnectionFactory("127.0.0.1", 1));
            context.register(SubmissionRateLimitConfig.class);
            context.refresh();
            client = context.getBean("submissionRedissonClient", RedissonClient.class);
            assertTrue(client.getConfig().isLazyInitialization());
            assertInstanceOf(LettuceConnectionFactory.class, context.getBean(RedisConnectionFactory.class));
            assertFalse(client.isShutdown());
        }
        assertTrue(client.isShutdown());
    }
}
