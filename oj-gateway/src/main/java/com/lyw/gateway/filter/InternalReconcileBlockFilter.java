package com.lyw.gateway.filter;

import com.lyw.common.core.utils.InternalReconcilePath;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

/** 在路径重写完成后、实际转发前禁止公开访问内部对账入口。 */
@Component
public class InternalReconcileBlockFilter implements GlobalFilter, Ordered {
    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        URI target = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
        if (InternalReconcilePath.matches(exchange.getRequest().getURI().getRawPath())
                || (target != null && InternalReconcilePath.matches(target.getRawPath()))) {
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    @Override public int getOrder() { return Ordered.LOWEST_PRECEDENCE - 10; }
}
