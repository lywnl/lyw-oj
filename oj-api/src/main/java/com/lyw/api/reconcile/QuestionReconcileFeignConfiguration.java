package com.lyw.api.reconcile;

import feign.Client;
import feign.Logger;
import feign.Request;
import feign.Retryer;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;

import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** 只由专用 Feign 子上下文加载，不注册为全局客户端配置。 */
public class QuestionReconcileFeignConfiguration {
    @Bean public Request.Options reconcileOptions(QuestionReconcileProperties properties) {
        properties.validateClient();
        return new Request.Options(properties.getConnectTimeoutMillis(), TimeUnit.MILLISECONDS,
                properties.getReadTimeoutMillis(), TimeUnit.MILLISECONDS, false);
    }
    @Bean public Retryer reconcileRetryer() { return Retryer.NEVER_RETRY; }
    @Bean public Logger.Level reconcileLogging() { return Logger.Level.NONE; }
    @Bean public Logger reconcileLogger() { return new Logger.NoOpLogger(); }
    @Bean public Client reconcileHttpClient() {
        return new Client.Default(null, null) {
            @Override public HttpURLConnection getConnection(URL url) throws IOException {
                HttpURLConnection connection = super.getConnection(url);
                // 本接口固定无请求体；流式发送阻止 HttpURLConnection 在 EOF 后自行重发 POST。
                connection.setFixedLengthStreamingMode(0);
                return connection;
            }
        };
    }
    @Bean public ErrorDecoder reconcileErrors() {
        return (method, response) -> new RemoteReconcileException(response.status());
    }

    public static final class RemoteReconcileException extends RuntimeException {
        private final int status;
        public RemoteReconcileException(int status) {
            super("对账内部调用失败，HTTP 状态=" + status);
            this.status = status;
        }
        public int getStatus() { return status; }
    }
}
