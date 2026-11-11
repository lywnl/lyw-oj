package com.lyw.system.questionsync;

import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

/** 调度已经外移：跨过原触发时间也不能自行读取题库。 */
class DailyReconciliationTest {
    @Test void localClockNeverStartsReconciliation() throws Exception {
        try (var fixture = new ReconcileTestSupport()) {
            for (long now : new long[]{1794333599999L, 1794333600000L, 1794420000000L, 0L}) fixture.runner.tick(now);
            verifyNoInteractions(fixture.reader);
            verify(fixture.source, times(4)).get(100);
        }
    }
}
