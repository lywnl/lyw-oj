package com.lyw.judge.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.netty.NettyDockerCmdExecFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DockerSandBoxPoolConfig {

    @Value("${sandbox.docker.host:tcp://localhost:2375}")
    private String dockerHost;

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

    /** 核心容器数（始终保持存活） */
    @Value("${sandbox.docker.pool.core-size:4}")
    private int corePoolSize;

    /** 最大容器数（高峰期扩容上限） */
    @Value("${sandbox.docker.pool.max-size:8}")
    private int maxPoolSize;

    /** 临时容器空闲存活时间（毫秒），超过后自动销毁 */
    @Value("${sandbox.docker.pool.keep-alive:60000}")
    private long keepAliveTime;

    /** 获取容器的等待超时时间（毫秒），超时后尝试扩容 */
    @Value("${sandbox.docker.pool.poll-timeout:3000}")
    private long pollTimeout;

    @Value("${sandbox.docker.name-prefix:oj-sandbox-jdk}")
    private String containerNamePrefix;

    @Bean
    public DockerClient createDockerClient() {
        DefaultDockerClientConfig clientConfig = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .build();
        return DockerClientBuilder
                .getInstance(clientConfig)
                .withDockerCmdExecFactory(new NettyDockerCmdExecFactory())
                .build();
    }

    @Bean(destroyMethod = "shutdown")
    public DockerSandBoxPool createDockerSandBoxPool(DockerClient dockerClient) {
        DockerSandBoxPool dockerSandBoxPool = new DockerSandBoxPool(
                dockerClient, sandboxImage, volumeDir,
                memoryLimit, memorySwapLimit, cpuLimit,
                corePoolSize, maxPoolSize, keepAliveTime, pollTimeout,
                containerNamePrefix);
        dockerSandBoxPool.initDockerPool();
        return dockerSandBoxPool;
    }
}
