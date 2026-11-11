package com.lyw.job.config;

import com.lyw.api.reconcile.QuestionReconcileFeignConfiguration;
import com.lyw.api.reconcile.QuestionReconcileProperties;
import com.lyw.api.reconcile.RemoteQuestionReconcileService;
import com.lyw.common.core.utils.InternalReconcilePath;
import com.lyw.job.handler.QuestionIndexReconcileJob;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.openfeign.FeignAutoConfiguration;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class QuestionReconcileConfigurationTest {
    static final String TOKEN = "isolated-feign-test-token-at-least-32";
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FeignAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class))
            .withUserConfiguration(QuestionReconcileJobConfiguration.class);

    @Test void disabledFeatureDoesNotRegisterClientOrHandler() {
        context.run(app -> {
            assertNull(app.getStartupFailure());
            assertEquals(0, app.getBeansOfType(RemoteQuestionReconcileService.class).size());
            assertEquals(0, app.getBeansOfType(QuestionIndexReconcileJob.class).size());
        });
    }

    @Test void validatesDefaultsTargetCredentialsAndTimeoutOrdering() {
        var p = new QuestionReconcileProperties();
        assertFalse(p.isEnabled()); assertEquals(600000, p.getTimeoutMillis());
        assertEquals(3000, p.getConnectTimeoutMillis()); assertEquals(660000, p.getReadTimeoutMillis());
        p.setEnabled(true); assertThrows(IllegalStateException.class, p::validateServer);
        p.setInternalToken(TOKEN); assertDoesNotThrow(p::validateServer);
        for (String target : new String[]{null, "", "oj-system", "ftp://system", "http://u:password@system",
                "http://system?token=value", "http://system#fragment"}) {
            p.setTargetUrl(target);
            var failure = assertThrows(IllegalStateException.class, p::validateClient);
            assertFalse(failure.getMessage().contains("password")); assertFalse(failure.getMessage().contains(TOKEN));
        }
        p.setTargetUrl("http://system:9201"); assertDoesNotThrow(p::validateClient);
        p.setReadTimeoutMillis(p.getTimeoutMillis()); assertThrows(IllegalStateException.class, p::validateClient);
        p.setReadTimeoutMillis(660000); p.setTimeoutMillis(0); assertThrows(IllegalStateException.class, p::validateServer);
    }

    @Test void cannotReuseXxlJobExecutorCredential() {
        context.withPropertyValues("question.reconcile.enabled=true", "question.reconcile.internal-token=" + TOKEN,
                "question.reconcile.target-url=http://localhost:1", "xxl.job.accessToken=" + TOKEN)
                .run(app -> assertNotNull(app.getStartupFailure()));
    }

    @Test void invalidTargetCannotLeakCredentialsDuringFeignRegistration() {
        String secret = "synthetic-password-must-stay-private";
        context.withPropertyValues("question.reconcile.enabled=true", "question.reconcile.internal-token=" + TOKEN,
                "question.reconcile.target-url=http://user:" + secret + "@localhost:INVALID")
                .run(app -> {
                    assertNotNull(app.getStartupFailure());
                    for (Throwable failure = app.getStartupFailure(); failure != null; failure = failure.getCause()) {
                        assertFalse(String.valueOf(failure.getMessage()).contains(secret));
                        assertFalse(String.valueOf(failure.getMessage()).contains(TOKEN));
                    }
                });
    }

    @Test void realFeignUsesDedicatedHeaderAndDecodesCompletion() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext(InternalReconcilePath.PATH, exchange -> {
            requests.incrementAndGet();
            assertEquals("POST", exchange.getRequestMethod());
            assertEquals(TOKEN, exchange.getRequestHeaders().getFirst(InternalReconcilePath.TOKEN_HEADER));
            byte[] body = "{\"code\":1000,\"msg\":\"成功\",\"data\":{\"differences\":4,\"repaired\":3,\"durationMillis\":12}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            enabled(server).run(app -> {
                assertNull(app.getStartupFailure());
                var result = app.getBean(RemoteQuestionReconcileService.class).reconcile(TOKEN);
                assertEquals(4, result.getData().differences()); assertEquals(3, result.getData().repaired());
            });
            assertEquals(1, requests.get());
        } finally { server.stop(0); }
    }

    @Test void remoteErrorIsNotRetriedAndRedirectCannotForwardTheCredential() throws Exception {
        for (int status : new int[]{503, 302}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            AtomicInteger requests = new AtomicInteger(), redirects = new AtomicInteger();
            server.createContext(InternalReconcilePath.PATH, exchange -> {
                requests.incrementAndGet();
                exchange.getResponseHeaders().set("Location", "/credential-trap");
                exchange.sendResponseHeaders(status, -1); exchange.close();
            });
            server.createContext("/credential-trap", exchange -> { redirects.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); });
            server.start();
            try {
                enabled(server).run(app -> {
                    assertNull(app.getStartupFailure());
                    var failure = assertThrows(QuestionReconcileFeignConfiguration.RemoteReconcileException.class,
                            () -> app.getBean(RemoteQuestionReconcileService.class).reconcile(TOKEN));
                    assertEquals(status, failure.getStatus()); assertFalse(failure.getMessage().contains(TOKEN));
                });
                assertEquals(1, requests.get()); assertEquals(0, redirects.get());
            } finally { server.stop(0); }
        }
    }

    private ApplicationContextRunner enabled(HttpServer server) {
        return context.withPropertyValues("question.reconcile.enabled=true", "question.reconcile.internal-token=" + TOKEN,
                "question.reconcile.target-url=http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test void underlyingHttpConnectionDoesNotReplayPostAfterLostResponse() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext(InternalReconcilePath.PATH, exchange -> {
            requests.incrementAndGet(); exchange.close();
        });
        server.start();
        try {
            enabled(server).run(app -> {
                assertNull(app.getStartupFailure());
                assertThrows(RuntimeException.class, () -> app.getBean(RemoteQuestionReconcileService.class).reconcile(TOKEN));
            });
            assertEquals(1, requests.get());
        } finally { server.stop(0); }
    }
}
