package com.lyw.system.questionsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.common.core.utils.InternalReconcilePath;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static com.lyw.system.questionsync.ReconcileTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class QuestionReconcileHttpTest {
    private final ObjectMapper json = new ObjectMapper();
    private MockMvc mvc(ReconcileTestSupport f) {
        return MockMvcBuilders.standaloneSetup(new QuestionReconcileController(f.runner))
                .addFilters(new QuestionReconcileAuthFilter(f.properties, json)).build();
    }

    @Test void missingWrongDuplicateAndUserTokensCannotStartReconciliation() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            f.start(); var mvc = mvc(f);
            var calls = List.of(post(InternalReconcilePath.PATH),
                    post(InternalReconcilePath.PATH).header(InternalReconcilePath.TOKEN_HEADER, "wrong"),
                    post(InternalReconcilePath.PATH).header(InternalReconcilePath.TOKEN_HEADER, TOKEN, TOKEN),
                    post(InternalReconcilePath.PATH).header("Authorization", "Bearer user-login-token"));
            for (var call : calls) {
                var response = mvc.perform(call).andReturn().getResponse();
                assertEquals(403, response.getStatus());
                var result = json.readTree(response.getContentAsString());
                assertEquals(3000, result.get("code").asInt()); assertTrue(result.get("data").isNull());
                assertFalse(response.getContentAsString().contains(TOKEN));
            }
            verifyNoInteractions(f.reader);
        }
    }

    @Test void encodedAndMatrixParameterPathsAreProtectedBeforeDispatch() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            f.start(); var mvc = mvc(f);
            for (String path : List.of("/internal/question-index/reconcile;v=1", "/internal/question-index/%72econcile",
                    "/internal/question-index/reconcile;x=%2Fignored",
                    "/internal%2Fquestion-index%2Freconcile", "/internal/question-index/reconcile/")) {
                assertEquals(403, mvc.perform(post(URI.create(path))).andReturn().getResponse().getStatus(), path);
            }
            verifyNoInteractions(f.reader);
        }
    }

    @Test void disabledAndNotRunningReturnUnavailable() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            var mvc = mvc(f);
            assertEquals(503, mvc.perform(post(InternalReconcilePath.PATH).header(InternalReconcilePath.TOKEN_HEADER, TOKEN))
                    .andReturn().getResponse().getStatus());
            f.properties.setEnabled(false); f.start();
            assertEquals(503, mvc.perform(post(InternalReconcilePath.PATH).header(InternalReconcilePath.TOKEN_HEADER, TOKEN))
                    .andReturn().getResponse().getStatus());
            verifyNoInteractions(f.reader);
        }
    }

    @Test void authorizedRequestReturnsActualCompletionStatistics() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            f.start();
            var response = mvc(f).perform(post(InternalReconcilePath.PATH).header(InternalReconcilePath.TOKEN_HEADER, TOKEN))
                    .andReturn().getResponse();
            assertEquals(200, response.getStatus());
            var result = json.readTree(response.getContentAsString());
            assertEquals(1000, result.get("code").asInt());
            assertEquals(0, result.get("data").get("differences").asInt());
            assertTrue(result.get("data").get("durationMillis").asLong() >= 0);
            verify(f.reader).page(0, 500);
        }
    }

    @Test void busyAndTimeoutHaveRealHttpStatusAndNullData() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.reader.page(0, 500)).thenAnswer(call -> { reading.countDown(); awaitUninterruptibly(release); return List.of(); });
            f.start(); var request = f.runner.requestReconciliation();
            try {
                assertTrue(reading.await(2, TimeUnit.SECONDS));
                var busy = mvc(f).perform(post(InternalReconcilePath.PATH).header(InternalReconcilePath.TOKEN_HEADER, TOKEN))
                        .andReturn().getResponse();
                assertEquals(409, busy.getStatus()); assertTrue(json.readTree(busy.getContentAsString()).get("data").isNull());
                release.countDown(); request.await();
            } finally { release.countDown(); }
        }
        try (var f = new ReconcileTestSupport()) {
            f.properties.setTimeoutMillis(100);
            CountDownLatch release = new CountDownLatch(1);
            when(f.reader.page(0, 500)).thenAnswer(call -> { awaitUninterruptibly(release); return List.of(); });
            f.start();
            try {
                var timedOut = mvc(f).perform(post(InternalReconcilePath.PATH).header(InternalReconcilePath.TOKEN_HEADER, TOKEN))
                        .andReturn().getResponse();
                assertEquals(504, timedOut.getStatus()); assertTrue(json.readTree(timedOut.getContentAsString()).get("data").isNull());
            } finally { release.countDown(); }
        }
    }
}
