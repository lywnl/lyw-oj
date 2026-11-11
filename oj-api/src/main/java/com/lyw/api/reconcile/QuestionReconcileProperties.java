package com.lyw.api.reconcile;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

@Getter
@Setter
@ConfigurationProperties("question.reconcile")
public class QuestionReconcileProperties {
    private boolean enabled;
    private String internalToken;
    private String targetUrl;
    private int timeoutMillis = 600000;
    private int connectTimeoutMillis = 3000;
    private int readTimeoutMillis = 660000;

    public void validateServer() {
        if (!enabled) return;
        if (internalToken == null || internalToken.length() < 32 || internalToken.length() > 256
                || internalToken.chars().anyMatch(character -> character <= 32 || character >= 127))
            throw new IllegalStateException("question.reconcile.internal-token 必须为 32～256 位非空白 ASCII 字符");
        if (timeoutMillis < 1) throw new IllegalStateException("question.reconcile.timeout-millis 必须为正数");
    }

    public void validateClient() {
        if (!enabled) return;
        validateServer();
        boolean valid = false;
        try {
            URI uri = URI.create(targetUrl == null ? "" : targetUrl);
            valid = ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException invalidAddress) { /* 不把配置值放入异常。 */ }
        if (!valid) throw new IllegalStateException("question.reconcile.target-url 必须明确指定唯一消费者的 HTTP(S) 地址，不能带凭据、查询或片段");
        if (connectTimeoutMillis < 1 || readTimeoutMillis <= timeoutMillis)
            throw new IllegalStateException("对账连接时限必须为正，读取时限必须大于服务端单轮时限");
    }
}
