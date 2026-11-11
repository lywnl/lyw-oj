package com.lyw.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Mono;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

class InternalReconcileBlockFilterTest {
    @Test void directPrefixedEncodedAndMatrixPathsNeverReachDownstream() {
        var filter = new InternalReconcileBlockFilter();
        for (String path : List.of("/internal/question-index/reconcile", "/system/internal/question-index/reconcile",
                "/oj-system/internal/question-index/reconcile", "/system/internal/question-index/%72econcile",
                "/system/internal%2Fquestion-index%2Freconcile", "/system/internal/question-index/reconcile;v=1",
                "/internal/question-index/reconcile;x=%2Fignored", "/system/internal/question-index/reconcile;x=%2Fignored",
                "/oj-system/internal/question-index/reconcile;x=%252Fignored",
                "/system/internal/other/../question-index/reconcile", "/system//internal/question-index/reconcile/")) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.method(org.springframework.http.HttpMethod.POST, URI.create(path))
                    .header("Authorization", "Bearer admin-login-token")
                    .header("X-Question-Reconcile-Token", "even-an-internal-token-cannot-cross-gateway").build());
            AtomicBoolean forwarded = new AtomicBoolean();
            filter.filter(exchange, ignored -> { forwarded.set(true); return Mono.empty(); }).block();
            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(), path);
            assertFalse(forwarded.get(), path);
        }
    }

    @Test void rewrittenPublicAliasCannotBypassTheBlock() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/public/alias").build());
        exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, URI.create("http://system/internal/question-index/reconcile"));
        new InternalReconcileBlockFilter().filter(exchange, ignored -> { fail("不允许转发"); return Mono.empty(); }).block();
        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertTrue(new InternalReconcileBlockFilter().getOrder() > 10150);
        assertTrue(new InternalReconcileBlockFilter().getOrder() < Integer.MAX_VALUE);
    }

    @Test void ordinaryRoutesAreUnchanged() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/system/question/list").build());
        AtomicBoolean forwarded = new AtomicBoolean();
        new InternalReconcileBlockFilter().filter(exchange, ignored -> { forwarded.set(true); return Mono.empty(); }).block();
        assertTrue(forwarded.get());
    }
}
