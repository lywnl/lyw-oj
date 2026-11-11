package com.lyw.judge.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SandboxLimitsTest {
    @Test
    void defaultsUseLinuxQuotaAndPreserveLongerQuestionLimits() {
        SandboxLimits limits = SandboxLimits.defaults();
        assertEquals(64, limits.pidsLimit());
        assertEquals(1_000_000_000L, limits.nanoCpus());
        assertEquals(5000L, limits.runtimeTimeoutMs(1000L));
        assertEquals(11000L, limits.runtimeTimeoutMs(10000L));
        assertEquals(1048576L, limits.outputMaxBytes());
    }

    @Test
    void invalidLimitsAndOverflowCannotDisableProtection() {
        assertThrows(IllegalArgumentException.class, () -> limits(0, 128, 10000, 1048576));
        assertThrows(IllegalArgumentException.class, () -> limits(1, -1, 10000, 1048576));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 128, 0, 1048576));
        assertThrows(IllegalArgumentException.class, () -> limits(1, 128, 10000, 0));
        assertThrows(ArithmeticException.class, () -> limits(Long.MAX_VALUE, 128, 10000, 1048576));
        assertThrows(ArithmeticException.class, () -> limits(1, 128, Long.MAX_VALUE, 1048576));
        for (Long invalid : new Long[]{null, 0L, -1L}) {
            assertThrows(IllegalArgumentException.class, () -> SandboxLimits.defaults().runtimeTimeoutMs(invalid));
        }
        assertThrows(ArithmeticException.class, () -> SandboxLimits.defaults().runtimeTimeoutMs(Long.MAX_VALUE));
    }

    private SandboxLimits limits(long cpu, long pids, long compile, long output) {
        return new SandboxLimits(100000000, 100000000, cpu, pids, compile, 5000, 1000, output, 10000);
    }
}
