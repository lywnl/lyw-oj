package com.lyw.job.handler;

import com.lyw.api.reconcile.QuestionReconcileProperties;
import com.lyw.api.reconcile.ReconcileResult;
import com.lyw.api.reconcile.RemoteQuestionReconcileService;
import com.lyw.common.core.domain.Result;
import com.xxl.job.core.context.XxlJobContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuestionIndexReconcileJobTest {
    private final RemoteQuestionReconcileService remote = mock(RemoteQuestionReconcileService.class);
    private final QuestionReconcileProperties properties = new QuestionReconcileProperties();
    private XxlJobContext context;
    @BeforeEach void setup() {
        properties.setInternalToken("isolated-job-test-token-at-least-32");
        context = new XxlJobContext(1, "", null, 0, 1);
        XxlJobContext.setXxlJobContext(context);
    }
    @AfterEach void clear() { XxlJobContext.setXxlJobContext(null); }

    @Test void completedRemoteResultIsReportedToXxlJob() {
        when(remote.reconcile(properties.getInternalToken())).thenReturn(Result.ok(new ReconcileResult(3, 2, 450)));
        new QuestionIndexReconcileJob(remote, properties).questionIndexReconcileHandler();
        assertEquals(200, context.getHandleCode());
        assertTrue(context.getHandleMsg().contains("差异=3")); assertTrue(context.getHandleMsg().contains("修复=2"));
        verify(remote, times(1)).reconcile(properties.getInternalToken());
    }
    @Test void failureResponseOrMissingResultIsNeverSuccess() {
        for (Result<ReconcileResult> result : java.util.Arrays.<Result<ReconcileResult>>asList(null, Result.fail(), Result.ok())) {
            when(remote.reconcile(properties.getInternalToken())).thenReturn(result);
            new QuestionIndexReconcileJob(remote, properties).questionIndexReconcileHandler();
            assertEquals(500, context.getHandleCode());
        }
    }
    @Test void exceptionIsNotRetriedOrLoggedWithItsSensitiveMessage() {
        when(remote.reconcile(properties.getInternalToken())).thenThrow(new IllegalStateException("secret-value-must-not-appear"));
        new QuestionIndexReconcileJob(remote, properties).questionIndexReconcileHandler();
        assertEquals(500, context.getHandleCode()); assertFalse(context.getHandleMsg().contains("secret-value"));
        verify(remote, times(1)).reconcile(properties.getInternalToken());
    }
    @Test void internalHttpFailureIncludesStatusWithoutRemoteBody() {
        when(remote.reconcile(properties.getInternalToken()))
                .thenThrow(new com.lyw.api.reconcile.QuestionReconcileFeignConfiguration.RemoteReconcileException(409));
        new QuestionIndexReconcileJob(remote, properties).questionIndexReconcileHandler();
        assertEquals(500, context.getHandleCode()); assertTrue(context.getHandleMsg().contains("HTTP=409"));
        assertFalse(context.getHandleMsg().contains(properties.getInternalToken()));
    }
}
