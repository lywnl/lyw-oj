package com.lyw.system.questionsync;

import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.system.domain.question.dto.QuestionAddDTO;
import com.lyw.system.domain.question.dto.QuestionEditDTO;
import com.lyw.system.manager.QuestionCacheManager;
import com.lyw.system.mapper.question.QuestionMapper;
import com.lyw.system.service.question.impl.QuestionServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuestionMaintenanceGateTest {
    @Test void allThreeWritesReturnExistingMaintenanceResultBeforeAnyStorageCall() {
        var environment = new MockEnvironment().withProperty("question.maintenance.enabled", "true");
        var service = new QuestionServiceImpl(); var mapper = mock(QuestionMapper.class); var cache = mock(QuestionCacheManager.class);
        ReflectionTestUtils.setField(service, "questionMapper", mapper); ReflectionTestUtils.setField(service, "questionCacheManager", cache);
        ReflectionTestUtils.setField(service, "maintenanceGate", new QuestionMaintenanceGate(environment));
        for (Runnable call : new Runnable[]{() -> service.add(new QuestionAddDTO()), () -> service.edit(new QuestionEditDTO()), () -> service.delete(1L)}) {
            ServiceException error = assertThrows(ServiceException.class, call::run);
            assertEquals(ResultCode.QUESTION_MAINTENANCE, error.getResultCode());
            assertEquals(3503, error.getResultCode().getCode()); assertEquals("题库维护中，请稍后重试", error.getMessage());
        }
        verifyNoInteractions(mapper, cache);
    }
    @Test void changingGateRejectsNewWritesButExistingOperationMustFinishSeparately() throws Exception {
        var environment = new MockEnvironment(); var gate = new QuestionMaintenanceGate(environment);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), finished = new CountDownLatch(1);
        Thread writer = new Thread(() -> gate.write(() -> {
            entered.countDown(); try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            finished.countDown();
            return true;
        }));
        writer.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS)); environment.setProperty("question.maintenance.enabled", "true");
            assertThrows(ServiceException.class, () -> gate.write(() -> true));
            assertFalse(finished.await(100, TimeUnit.MILLISECONDS));
            release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS));
            environment.setProperty("question.maintenance.enabled", "false");
            assertEquals("恢复写入", gate.write(() -> "恢复写入"));
        } finally { release.countDown(); writer.join(1000); }
    }
}
