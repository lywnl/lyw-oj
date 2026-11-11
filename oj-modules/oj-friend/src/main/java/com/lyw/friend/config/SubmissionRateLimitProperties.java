package com.lyw.friend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.regex.Pattern;

/** 提交频率规则；参数调整需停服统一处理已有 Redis 状态。 */
@ConfigurationProperties(prefix = "judge.submit-limit")
public class SubmissionRateLimitProperties {
    private static final Pattern ENVIRONMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private String environment = "dev";
    private Duration userInterval = Duration.ofSeconds(5);
    private Duration userIdleTime = Duration.ofMinutes(10);
    private int globalPermits = 3;
    private Duration globalInterval = Duration.ofSeconds(1);

    public void validate() {
        if (environment == null || !ENVIRONMENT.matcher(environment).matches()) {
            throw new IllegalArgumentException("限流环境名称须为 1～64 位字母、数字、下划线或连字符，且以字母或数字开头");
        }
        positiveMillis(userInterval, "用户限流周期");
        positiveMillis(userIdleTime, "用户限流空闲期限");
        positiveMillis(globalInterval, "全局限流周期");
        if (globalPermits <= 0) throw new IllegalArgumentException("全局限流额度必须大于零");
        if (userIdleTime.compareTo(userInterval) < 0) {
            throw new IllegalArgumentException("用户限流空闲期限不能小于用户限流周期");
        }
    }

    private static void positiveMillis(Duration duration, String name) {
        try {
            if (duration == null || duration.toMillis() <= 0) {
                throw new IllegalArgumentException(name + "必须至少为 1 毫秒");
            }
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + "超出支持范围");
        }
    }

    public String keyPrefix() { return "oj:" + environment + ":submit:"; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public Duration getUserInterval() { return userInterval; }
    public void setUserInterval(Duration userInterval) { this.userInterval = userInterval; }
    public Duration getUserIdleTime() { return userIdleTime; }
    public void setUserIdleTime(Duration userIdleTime) { this.userIdleTime = userIdleTime; }
    public int getGlobalPermits() { return globalPermits; }
    public void setGlobalPermits(int globalPermits) { this.globalPermits = globalPermits; }
    public Duration getGlobalInterval() { return globalInterval; }
    public void setGlobalInterval(Duration globalInterval) { this.globalInterval = globalInterval; }
}
