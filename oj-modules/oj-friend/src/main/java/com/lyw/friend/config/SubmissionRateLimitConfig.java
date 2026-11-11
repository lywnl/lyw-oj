package com.lyw.friend.config;

import io.lettuce.core.ClientOptions;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.redisson.config.SslVerificationMode;
import org.redisson.config.TransportMode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SubmissionRateLimitProperties.class)
public class SubmissionRateLimitConfig {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient submissionRedissonClient(RedisConnectionFactory connectionFactory,
                                                   SubmissionRateLimitProperties properties) {
        properties.validate();
        return Redisson.create(clientConfig(connectionFactory));
    }

    /** 只复制连接信息，不读取业务键，也不在配置阶段连接 Redis。 */
    public static Config clientConfig(RedisConnectionFactory connectionFactory) {
        if (!(connectionFactory instanceof LettuceConnectionFactory lettuce)) {
            throw new IllegalArgumentException("提交限流仅支持现有 Lettuce 单实例 Redis 连接");
        }
        RedisStandaloneConfiguration standalone = standaloneConfiguration(lettuce);
        LettuceClientConfiguration client = lettuce.getClientConfiguration();
        if (client.isStartTls() || client.getRedisCredentialsProviderFactory().isPresent()) {
            throw new IllegalArgumentException("提交限流不支持 StartTLS 或动态认证配置");
        }
        if (client.isUseSsl() && client.getClientOptions().map(ClientOptions::getSslOptions)
                .filter(options -> options != ClientOptions.DEFAULT_SSL_OPTIONS).isPresent()) {
            // 自定义证书、协议或 SSL 上下文不能静默替换为 JDK 默认信任配置。
            throw new IllegalArgumentException("提交限流尚未适配自定义 Lettuce SSL 选项");
        }

        String host = standalone.getHostName();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        String address = (client.isUseSsl() ? "rediss://" : "redis://") + host + ":" + standalone.getPort();
        Config config = new Config();
        config.setLazyInitialization(true);
        config.setTransportMode(TransportMode.NIO);
        config.setCodec(StringCodec.INSTANCE);
        SingleServerConfig server = config.useSingleServer()
                .setAddress(address)
                .setDatabase(standalone.getDatabase())
                .setUsername(standalone.getUsername())
                .setPassword(standalone.getPassword().isPresent() ? new String(standalone.getPassword().get()) : null)
                .setConnectTimeout(1000)
                .setTimeout(1000)
                .setRetryAttempts(0)
                .setConnectionPoolSize(8)
                .setConnectionMinimumIdleSize(1);
        server.setSslVerificationMode(client.isVerifyPeer() ? SslVerificationMode.STRICT : SslVerificationMode.NONE);
        return config;
    }

    private static RedisStandaloneConfiguration standaloneConfiguration(LettuceConnectionFactory lettuce) {
        // Spring Data Redis 3.0 的公开 standalone getter 在通用构造器下可能返回默认配置。
        // 读取固定版本的生效配置；版本不兼容时明确失败，避免误连 localhost 或丢失认证。
        Field field = ReflectionUtils.findField(LettuceConnectionFactory.class, "configuration", RedisConfiguration.class);
        if (field == null) throw new IllegalStateException("无法核对现有 Redis 生效配置，请检查 Spring Data Redis 版本");
        ReflectionUtils.makeAccessible(field);
        Object effective = ReflectionUtils.getField(field, lettuce);
        if (effective == null) return lettuce.getStandaloneConfiguration();
        if (effective instanceof RedisStandaloneConfiguration standalone) return standalone;
        throw new IllegalArgumentException("提交限流仅支持单实例 Redis，不能复用当前连接拓扑");
    }
}
