package com.lyw.friend.service.user;

import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.friend.config.SubmissionRateLimitProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** 只负责提交频率检查，按提交尝试计数，不承担任务并发或名额释放。 */
@Service
@Slf4j
public class SubmissionRateLimiter {
    private final RedissonClient redisson;
    private final String prefix;
    private final Duration userInterval;
    private final Duration userIdleTime;
    private final int globalPermits;
    private final Duration globalInterval;

    public SubmissionRateLimiter(@Qualifier("submissionRedissonClient") RedissonClient redisson,
                                 SubmissionRateLimitProperties properties) {
        properties.validate();
        this.redisson = redisson;
        this.prefix = properties.keyPrefix();
        this.userInterval = properties.getUserInterval();
        this.userIdleTime = properties.getUserIdleTime();
        this.globalPermits = properties.getGlobalPermits();
        this.globalInterval = properties.getGlobalInterval();
    }

    public void check(Long userId) {
        if (userId == null || userId <= 0) throw new ServiceException(ResultCode.FAILED_UNAUTHORIZED);
        try {
            RRateLimiter user = redisson.getRateLimiter(prefix + "user:" + userId);
            // false 表示已有规则，不代表没有额度；普通请求不能重置已有状态。
            user.trySetRate(RateType.OVERALL, 1, userInterval, userIdleTime);
            if (!user.tryAcquire()) {
                log.debug("提交频率检查拒绝：用户额度耗尽");
                throw new ServiceException(ResultCode.FAILED_SUBMIT_FREQUENT);
            }
            RRateLimiter global = redisson.getRateLimiter(prefix + "global");
            global.trySetRate(RateType.OVERALL, globalPermits, globalInterval);
            if (!global.tryAcquire()) {
                log.debug("提交频率检查拒绝：全局额度耗尽");
                throw new ServiceException(ResultCode.FAILED_SUBMIT_BUSY);
            }
        } catch (RedisException failure) {
            // 不记录原始异常消息或堆栈，避免第三方错误携带连接认证信息。
            log.warn("提交限流 Redis 检查失败，异常类型={}", failure.getClass().getSimpleName());
            throw new ServiceException(ResultCode.FAILED_SUBMIT_LIMIT_UNAVAILABLE);
        }
    }
}
