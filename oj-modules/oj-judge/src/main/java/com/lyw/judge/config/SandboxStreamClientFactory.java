package com.lyw.judge.config;

import com.github.dockerjava.api.DockerClient;

/** 每个 Worker 独占流客户端，异常关闭不会中断其他 Worker。 */
@FunctionalInterface
public interface SandboxStreamClientFactory {
    DockerClient create();
}
