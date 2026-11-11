package com.lyw.judge.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.lyw.common.core.constants.JudgeConstants;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

@Configuration
public class DockerSandBoxPoolConfig {
    @Value("${sandbox.docker.host:tcp://localhost:2375}")
    private String dockerHost;

    @Value("${sandbox.docker.api-version:1.41}")
    private String dockerApiVersion;

    @Value("${sandbox.docker.image:azul/zulu-openjdk:17}")
    private String sandboxImage;

    @Value("${sandbox.docker.volume:/usr/share/java}")
    private String volumeDir;

    @Value("${sandbox.limit.memory:100000000}")
    private Long memoryLimit;

    @Value("${sandbox.limit.memory-swap:100000000}")
    private Long memorySwapLimit;

    @Value("${sandbox.limit.cpu:1}")
    private Long cpuLimit;

    @Value("${sandbox.limit.pids:64}")
    private Long pidsLimit;

    @Value("${sandbox.limit.compile-timeout-ms:10000}")
    private Long compileTimeoutMs;

    @Value("${sandbox.limit.time:5}")
    private Long timeLimitSeconds;

    @Value("${sandbox.limit.run-grace-ms:1000}")
    private Long runGraceMs;

    @Value("${sandbox.limit.output-max-bytes:1048576}")
    private Long outputMaxBytes;

    @Value("${sandbox.limit.cleanup-timeout-ms:10000}")
    private Long cleanupTimeoutMs;

    @Value("${sandbox.docker.pool.core-size:4}")
    private int corePoolSize;

    @Value("${sandbox.docker.pool.max-size:8}")
    private int maxPoolSize;

    @Value("${sandbox.docker.pool.keep-alive:60000}")
    private long keepAliveTime;

    @Value("${sandbox.docker.pool.queue-capacity:16}")
    private int queueCapacity;

    @Value("${sandbox.docker.name-prefix:oj-sandbox-jdk}")
    private String containerNamePrefix;

    public int getQueueCapacity() { return queueCapacity; }

    @Bean
    public SandboxLimits sandboxLimits() {
        return new SandboxLimits(memoryLimit, memorySwapLimit, cpuLimit, pidsLimit, compileTimeoutMs,
                Math.multiplyExact(timeLimitSeconds, 1000L), runGraceMs, outputMaxBytes, cleanupTimeoutMs);
    }

    @Bean(name = "sandboxControlDockerClient", destroyMethod = "close")
    public DockerClient createControlDockerClient(SandboxLimits limits) {
        return newControlClient(dockerHost, dockerApiVersion, limits.cleanupTimeoutMs());
    }

    @Bean
    public SandboxStreamClientFactory sandboxStreamClientFactory() {
        return () -> newStreamClient(dockerHost, dockerApiVersion);
    }

    @Bean(destroyMethod = "shutdown")
    public DockerSandBoxPool createDockerSandBoxPool(
            @Qualifier("sandboxControlDockerClient") DockerClient controlClient,
            SandboxStreamClientFactory streamClients, SandboxLimits limits) {
        SandboxFileManager files = new SandboxFileManager(
                Path.of(System.getProperty("user.dir"), JudgeConstants.CODE_DIR_POOL), limits.cleanupTimeoutMs());
        DockerSandBoxPool pool = new DockerSandBoxPool(controlClient, streamClients, sandboxImage, volumeDir, limits,
                corePoolSize, maxPoolSize, queueCapacity, keepAliveTime, containerNamePrefix, files);
        pool.initDockerPool();
        return pool;
    }

    /** 控制请求有独立超时，不能用它裁断合法的长时间执行流。 */
    public static DockerClient newControlClient(String host, String apiVersion, long responseTimeoutMs) {
        SandboxLimits.checkedNanos(responseTimeoutMs);
        return newHttpClient(host, apiVersion, Duration.ofMillis(responseTimeoutMs));
    }

    /** 流客户端由一个 Worker 独占，回收时关闭所有属于该 Worker 的连接。 */
    public static DockerClient newStreamClient(String host, String apiVersion) {
        // 流由有界启动/执行等待控制期限，短响应超时会误杀合法的无输出程序。
        return newHttpClient(host, apiVersion, Duration.ZERO);
    }

    private static DockerClient newHttpClient(String host, String apiVersion, Duration responseTimeout) {
        DefaultDockerClientConfig config = clientConfig(host, apiVersion);
        ApacheDockerHttpClient http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(3)).responseTimeout(responseTimeout).build();
        return DockerClientBuilder.getInstance(config).withDockerHttpClient(http).build();
    }

    private static DefaultDockerClientConfig clientConfig(String host, String apiVersion) {
        return DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(host).withApiVersion(apiVersion).build();
    }
}
