package com.lyw.judge.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.netty.NettyDockerCmdExecFactory;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.judge.domain.JudgeTask;
import com.lyw.judge.domain.SandBoxExecuteResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerSandBoxPoolIntegrationTest {

    @Test
    void realDockerPoolConsumesTasksAndHonorsCapacity() throws Exception {
        String dockerHost = System.getProperty("docker.host");
        if (dockerHost == null || dockerHost.isBlank()) {
            throw new IllegalStateException("必须通过 -Ddocker.host 指定可用 Docker API 端点");
        }
        DockerClient client = DockerClientBuilder.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder()
                        .withDockerHost(dockerHost).build())
                .withDockerCmdExecFactory(new NettyDockerCmdExecFactory()).build();
        DockerSandBoxPool pool = new DockerSandBoxPool(client, "azul/zulu-openjdk:17",
                "/usr/share/java", 100000000L, 100000000L, 1L,
                1, 2, 1, 200L, "oj-test-sandbox");
        try {
            pool.initDockerPool();
            JudgeTask first = task();
            JudgeTask second = task();
            assertTrue(pool.submit(first));
            assertTrue(pool.submit(second));
            assertEquals(CodeRunStatus.SUCCEED, first.getFuture().get(10, TimeUnit.SECONDS).getRunStatus());
            assertEquals(CodeRunStatus.SUCCEED, second.getFuture().get(10, TimeUnit.SECONDS).getRunStatus());
            assertTrue(pool.getWorkerCount() <= 2);
        } finally {
            pool.shutdown();
            client.close();
        }
    }

    @Test
    void realDockerPoolReclaimsIdleTemporaryWorker() throws Exception {
        String dockerHost = System.getProperty("docker.host");
        if (dockerHost == null || dockerHost.isBlank()) {
            throw new IllegalStateException("必须通过 -Ddocker.host 指定可用 Docker API 端点");
        }
        DockerClient client = DockerClientBuilder.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(dockerHost).build())
                .withDockerCmdExecFactory(new NettyDockerCmdExecFactory()).build();
        DockerSandBoxPool pool = new DockerSandBoxPool(client, "azul/zulu-openjdk:17",
                "/usr/share/java", 100000000L, 100000000L, 1L,
                1, 2, 1, 200L, "oj-test-reclaim");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        try {
            pool.initDockerPool();
            JudgeTask first = new JudgeTask(1L, "class Main {}", List.of(""), id -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return SandBoxExecuteResult.success(CodeRunStatus.SUCCEED, List.of(), 0L, 0L);
            }, null);
            JudgeTask second = task();
            JudgeTask third = task();
            assertTrue(pool.submit(first));
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            assertTrue(pool.submit(second));
            assertTrue(pool.submit(third));
            assertEquals(2, pool.getWorkerCount());
            releaseFirst.countDown();
            first.getFuture().get(10, TimeUnit.SECONDS);
            second.getFuture().get(10, TimeUnit.SECONDS);
            third.getFuture().get(10, TimeUnit.SECONDS);
            Thread.sleep(500L);
            assertEquals(1, pool.getWorkerCount());
        } finally {
            pool.shutdown();
            client.close();
        }
    }

    @Test
    void realDockerPoolRejectsWhenQueueAndWorkersAreFull() throws Exception {
        String dockerHost = System.getProperty("docker.host");
        if (dockerHost == null || dockerHost.isBlank()) {
            throw new IllegalStateException("必须通过 -Ddocker.host 指定可用 Docker API 端点");
        }
        DockerClient client = DockerClientBuilder.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(dockerHost).build())
                .withDockerCmdExecFactory(new NettyDockerCmdExecFactory()).build();
        DockerSandBoxPool pool = new DockerSandBoxPool(client, "azul/zulu-openjdk:17",
                "/usr/share/java", 100000000L, 100000000L, 1L,
                1, 1, 1, 200L, "oj-test-reject");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            pool.initDockerPool();
            JudgeTask first = new JudgeTask(1L, "class Main {}", List.of(""), id -> {
                started.countDown();
                try { release.await(); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return SandBoxExecuteResult.success(CodeRunStatus.SUCCEED, List.of(), 0L, 0L);
            }, null);
            JudgeTask queued = task();
            JudgeTask rejected = task();
            assertTrue(pool.submit(first));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(pool.submit(queued));
            assertTrue(!pool.submit(rejected));
            assertEquals(com.lyw.judge.domain.JudgeTaskState.REJECTED, rejected.getState());
            assertEquals(CodeRunStatus.SYSTEM_BUSY,
                    rejected.getFuture().get(2, TimeUnit.SECONDS).getRunStatus());
            release.countDown();
            first.getFuture().get(10, TimeUnit.SECONDS);
            queued.getFuture().get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
            client.close();
        }
    }

    @Test
    void realDockerPoolRebuildsCoreWorkerAfterContainerFailure() throws Exception {
        String dockerHost = System.getProperty("docker.host");
        if (dockerHost == null || dockerHost.isBlank()) {
            throw new IllegalStateException("必须通过 -Ddocker.host 指定可用 Docker API 端点");
        }
        DockerClient client = DockerClientBuilder.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(dockerHost).build())
                .withDockerCmdExecFactory(new NettyDockerCmdExecFactory()).build();
        DockerSandBoxPool pool = new DockerSandBoxPool(client, "azul/zulu-openjdk:17",
                "/usr/share/java", 100000000L, 100000000L, 1L,
                1, 1, 1, 200L, "oj-test-rebuild");
        try {
            pool.initDockerPool();
            Container container = client.listContainersCmd().withShowAll(true).exec().stream()
                    .filter(item -> item.getNames() != null && item.getNames().length > 0
                            && item.getNames()[0].endsWith("oj-test-rebuild-0"))
                    .findFirst().orElseThrow();
            client.stopContainerCmd(container.getId()).exec();

            JudgeTask failed = task();
            assertTrue(pool.submit(failed));
            failed.getFuture().get(10, TimeUnit.SECONDS);
            assertEquals(com.lyw.judge.domain.JudgeTaskState.FAILED, failed.getState());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (pool.getWorkerCount() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(100L);
            }
            assertEquals(1, pool.getWorkerCount());
            JudgeTask recovered = task();
            assertTrue(pool.submit(recovered));
            assertEquals(CodeRunStatus.SUCCEED, recovered.getFuture().get(10, TimeUnit.SECONDS).getRunStatus());
        } finally {
            pool.shutdown();
            client.close();
        }
    }

    @Test
    void realDockerPoolReusesHealthyContainerAfterUserFailure() throws Exception {
        String dockerHost = System.getProperty("docker.host");
        if (dockerHost == null || dockerHost.isBlank()) {
            throw new IllegalStateException("必须通过 -Ddocker.host 指定可用 Docker API 端点");
        }
        DockerClient client = DockerClientBuilder.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(dockerHost).build())
                .withDockerCmdExecFactory(new NettyDockerCmdExecFactory()).build();
        DockerSandBoxPool pool = new DockerSandBoxPool(client, "azul/zulu-openjdk:17",
                "/usr/share/java", 100000000L, 100000000L, 1L,
                1, 1, 1, 200L, "oj-test-user-failure");
        try {
            pool.initDockerPool();
            JudgeTask failed = new JudgeTask(1L, "class Main {}", List.of(""), id ->
                    SandBoxExecuteResult.fail(CodeRunStatus.COMPILE_FAILED), null);
            assertTrue(pool.submit(failed));
            assertEquals(CodeRunStatus.COMPILE_FAILED, failed.getFuture().get(10, TimeUnit.SECONDS).getRunStatus());
            assertEquals(1, pool.getWorkerCount());

            JudgeTask next = task();
            assertTrue(pool.submit(next));
            assertEquals(CodeRunStatus.SUCCEED, next.getFuture().get(10, TimeUnit.SECONDS).getRunStatus());
            assertEquals(1, pool.getWorkerCount());
        } finally {
            pool.shutdown();
            client.close();
        }
    }

    @Test
    void realDockerPoolRebuildsAfterWorkerExecutionException() throws Exception {
        String dockerHost = System.getProperty("docker.host");
        if (dockerHost == null || dockerHost.isBlank()) {
            throw new IllegalStateException("必须通过 -Ddocker.host 指定可用 Docker API 端点");
        }
        DockerClient client = DockerClientBuilder.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(dockerHost).build())
                .withDockerCmdExecFactory(new NettyDockerCmdExecFactory()).build();
        DockerSandBoxPool pool = new DockerSandBoxPool(client, "azul/zulu-openjdk:17",
                "/usr/share/java", 100000000L, 100000000L, 1L,
                1, 1, 1, 200L, "oj-test-exec-failure");
        try {
            pool.initDockerPool();
            JudgeTask failed = new JudgeTask(1L, "class Main {}", List.of(""),
                    id -> { throw new IllegalStateException("exec failed"); }, null);
            assertTrue(pool.submit(failed));
            failed.getFuture().get(10, TimeUnit.SECONDS);
            assertEquals(com.lyw.judge.domain.JudgeTaskState.FAILED, failed.getState());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (pool.getWorkerCount() < 1 && System.nanoTime() < deadline) {
                Thread.sleep(100L);
            }
            assertEquals(1, pool.getWorkerCount());
        } finally {
            pool.shutdown();
            client.close();
        }
    }

    @Test
    void realDockerPoolFailsStartupWhenCoreContainerCannotBeCreated() throws Exception {
        String dockerHost = System.getProperty("docker.host");
        if (dockerHost == null || dockerHost.isBlank()) {
            throw new IllegalStateException("必须通过 -Ddocker.host 指定可用 Docker API 端点");
        }
        DockerClient client = DockerClientBuilder.getInstance(
                DefaultDockerClientConfig.createDefaultConfigBuilder().withDockerHost(dockerHost).build())
                .withDockerCmdExecFactory(new NettyDockerCmdExecFactory()).build();
        DockerSandBoxPool pool = new DockerSandBoxPool(client, "invalid/image:does-not-exist",
                "/usr/share/java", 100000000L, 100000000L, 1L,
                1, 1, 1, 200L, "oj-test-startup-failure");
        try {
            assertThrows(IllegalStateException.class, pool::initDockerPool);
            assertEquals(0, pool.getWorkerCount());
        } finally {
            pool.shutdown();
            client.close();
        }
    }

    private JudgeTask task() {
        return new JudgeTask(1L, "class Main {}", List.of(""),
                id -> SandBoxExecuteResult.success(CodeRunStatus.SUCCEED, List.of(), 0L, 0L), null);
    }
}
