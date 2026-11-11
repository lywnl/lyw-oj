package com.lyw.friend.service;

import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.friend.config.SubmissionRateLimitProperties;
import com.lyw.friend.service.user.SubmissionRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.RedisTimeoutException;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SubmissionRateLimiterTest {
    private final RedissonClient client = mock(RedissonClient.class);
    private final RRateLimiter user = mock(RRateLimiter.class);
    private final RRateLimiter global = mock(RRateLimiter.class);
    private SubmissionRateLimiter limiter;

    @BeforeEach
    void setup() {
        when(client.getRateLimiter("oj:test:submit:user:7")).thenReturn(user);
        when(client.getRateLimiter("oj:test:submit:global")).thenReturn(global);
        SubmissionRateLimitProperties properties = new SubmissionRateLimitProperties();
        properties.setEnvironment("test");
        limiter = new SubmissionRateLimiter(client, properties);
    }

    @Test
    void existingRulesReturningFalseStillAcquireInTheApprovedOrder() {
        when(user.tryAcquire()).thenReturn(true);
        when(global.tryAcquire()).thenReturn(true);
        limiter.check(7L);
        var order = inOrder(client, user, global);
        order.verify(client).getRateLimiter("oj:test:submit:user:7");
        order.verify(user).trySetRate(RateType.OVERALL, 1, Duration.ofSeconds(5), Duration.ofMinutes(10));
        order.verify(user).tryAcquire();
        order.verify(client).getRateLimiter("oj:test:submit:global");
        order.verify(global).trySetRate(RateType.OVERALL, 3, Duration.ofSeconds(1));
        order.verify(global).tryAcquire();
        order.verifyNoMoreInteractions();
    }

    @Test
    void userRejectionNeverLooksUpOrConsumesGlobalQuota() {
        assertCode(ResultCode.FAILED_SUBMIT_FREQUENT, () -> limiter.check(7L));
        verify(client, never()).getRateLimiter("oj:test:submit:global");
        verifyNoInteractions(global);
    }

    @Test
    void globalRejectionDoesNotReleaseTheUserPermit() {
        when(user.tryAcquire()).thenReturn(true);
        assertCode(ResultCode.FAILED_SUBMIT_BUSY, () -> limiter.check(7L));
        verify(user, never()).release(anyLong());
        verify(global, never()).release(anyLong());
    }

    @Test
    void convertsConnectionAndCommandFailuresAtEitherLayer() {
        when(user.trySetRate(any(), anyLong(), any(Duration.class), any(Duration.class)))
                .thenThrow(new RedisConnectionException("测试连接失败"));
        assertCode(ResultCode.FAILED_SUBMIT_LIMIT_UNAVAILABLE, () -> limiter.check(7L));
        reset(user);
        when(user.tryAcquire()).thenThrow(new RedisTimeoutException("测试命令超时"));
        assertCode(ResultCode.FAILED_SUBMIT_LIMIT_UNAVAILABLE, () -> limiter.check(7L));
        reset(user);
        when(user.tryAcquire()).thenReturn(true);
        when(global.trySetRate(any(), anyLong(), any(Duration.class)))
                .thenThrow(new RedisConnectionException("测试全局初始化失败"));
        assertCode(ResultCode.FAILED_SUBMIT_LIMIT_UNAVAILABLE, () -> limiter.check(7L));
        reset(global);
        when(global.tryAcquire()).thenThrow(new RedisTimeoutException("测试全局命令超时"));
        assertCode(ResultCode.FAILED_SUBMIT_LIMIT_UNAVAILABLE, () -> limiter.check(7L));
        verify(user, never()).release(anyLong());
    }

    @Test
    void invalidIdentityDoesNotTouchRedis() {
        assertCode(ResultCode.FAILED_UNAUTHORIZED, () -> limiter.check(null));
        assertCode(ResultCode.FAILED_UNAUTHORIZED, () -> limiter.check(0L));
        assertCode(ResultCode.FAILED_UNAUTHORIZED, () -> limiter.check(-1L));
        verifyNoInteractions(client, user, global);
    }

    @Test
    void diagnosticsDoNotExposeRedisErrorMessagesOrStackTraces() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SubmissionRateLimiter.class);
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            when(user.tryAcquire()).thenThrow(new RedisTimeoutException("password=unit-secret; source=private-code"));
            assertCode(ResultCode.FAILED_SUBMIT_LIMIT_UNAVAILABLE, () -> limiter.check(7L));
            assertFalse(appender.list.isEmpty());
            assertTrue(appender.list.stream().noneMatch(event -> event.getThrowableProxy() != null
                    || event.getFormattedMessage().contains("unit-secret")
                    || event.getFormattedMessage().contains("private-code")));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void runtimePropertyChangesDoNotSilentlyResetTheActiveRules() {
        SubmissionRateLimitProperties properties = new SubmissionRateLimitProperties();
        properties.setEnvironment("test");
        SubmissionRateLimiter snapshot = new SubmissionRateLimiter(client, properties);
        properties.setUserInterval(Duration.ofSeconds(1));
        properties.setGlobalPermits(100);
        when(user.tryAcquire()).thenReturn(true);
        when(global.tryAcquire()).thenReturn(true);
        snapshot.check(7L);
        verify(user).trySetRate(RateType.OVERALL, 1, Duration.ofSeconds(5), Duration.ofMinutes(10));
        verify(global).trySetRate(RateType.OVERALL, 3, Duration.ofSeconds(1));
    }

    private void assertCode(ResultCode expected, Runnable action) {
        assertEquals(expected, assertThrows(ServiceException.class, action::run).getResultCode());
    }
}
