package com.lyw.judge.config;

/** 池化沙箱的固定限制，时间统一使用毫秒。 */
public record SandboxLimits(long memoryLimit, long memorySwapLimit, long cpuLimit,
                            long pidsLimit, long compileTimeoutMs, long minimumRunMs,
                            long runGraceMs, long outputMaxBytes, long cleanupTimeoutMs) {
    public SandboxLimits {
        if (memoryLimit <= 0 || (memorySwapLimit != -1 && memorySwapLimit < memoryLimit)
                || cpuLimit <= 0 || pidsLimit <= 0 || compileTimeoutMs <= 0
                || minimumRunMs <= 0 || runGraceMs <= 0 || cleanupTimeoutMs <= 0
                || outputMaxBytes <= 0 || outputMaxBytes > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException("沙箱限制必须为有效正数，swap 不得小于内存限制");
        }
        Math.multiplyExact(cpuLimit, 1_000_000_000L);
        checkedNanos(compileTimeoutMs);
        checkedNanos(minimumRunMs);
        checkedNanos(runGraceMs);
        checkedNanos(cleanupTimeoutMs);
    }

    public static SandboxLimits defaults() {
        return new SandboxLimits(100000000L, 100000000L, 1L, 64L,
                10000L, 5000L, 1000L, 1048576L, 10000L);
    }

    public long nanoCpus() {
        return Math.multiplyExact(cpuLimit, 1_000_000_000L);
    }

    public long runtimeTimeoutMs(Long questionTimeLimitMs) {
        if (questionTimeLimitMs == null || questionTimeLimitMs <= 0) {
            throw new IllegalArgumentException("题目时限必须为正数毫秒值");
        }
        long timeout = Math.max(minimumRunMs, Math.addExact(questionTimeLimitMs, runGraceMs));
        checkedNanos(timeout);
        return timeout;
    }

    public static long checkedNanos(long milliseconds) {
        if (milliseconds <= 0) throw new IllegalArgumentException("等待时间必须为正数");
        return Math.multiplyExact(milliseconds, 1_000_000L);
    }
}
