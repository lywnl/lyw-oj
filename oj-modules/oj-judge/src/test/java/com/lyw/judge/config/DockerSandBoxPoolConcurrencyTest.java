package com.lyw.judge.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.Image;
import com.github.dockerjava.api.model.HostConfig;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.judge.domain.JudgeTask;
import com.lyw.judge.domain.SandBoxExecuteResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DockerSandBoxPoolConcurrencyTest {

    @Test
    void substituteClientKeepsSingleExecutionAndCapacityAccounting() throws Exception {
        DockerClient client = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        Image image = mock(Image.class);
        when(image.getRepoTags()).thenReturn(new String[]{"azul/zulu-openjdk:17"});
        when(client.listImagesCmd().exec()).thenReturn(List.of(image));
        when(client.listContainersCmd().withShowAll(true).exec()).thenReturn(List.of());
        AtomicInteger sequence = new AtomicInteger();
        CreateContainerCmd create = mock(CreateContainerCmd.class, RETURNS_SELF);
        when(client.createContainerCmd(anyString())).thenReturn(create);
        when(create.exec()).thenAnswer(invocation -> {
            CreateContainerResponse response = new CreateContainerResponse();
            response.setId("container-" + sequence.incrementAndGet());
            return response;
        });
        InspectContainerResponse.ContainerState state = mock(InspectContainerResponse.ContainerState.class);
        when(state.getRunning()).thenReturn(true);
        when(client.inspectContainerCmd(anyString()).exec().getState()).thenReturn(state);

        DockerSandBoxPool pool = new DockerSandBoxPool(client, "azul/zulu-openjdk:17",
                "/usr/share/java", 100000000L, 100000000L, 1L,
                1, 2, 1, 200L, "substitute-concurrency");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        try {
            pool.initDockerPool();
            JudgeTask first = blockingTask(started, release, executions);
            JudgeTask queued = blockingTask(new CountDownLatch(0), new CountDownLatch(0), executions);
            JudgeTask expanded = blockingTask(new CountDownLatch(0), new CountDownLatch(0), executions);
            JudgeTask rejected = blockingTask(new CountDownLatch(0), new CountDownLatch(0), executions);
            assertTrue(pool.submit(first));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(pool.submit(queued));
            assertTrue(pool.submit(expanded));
            assertTrue(!pool.submit(rejected));
            assertTrue(executions.get() <= 2);
            release.countDown();
            first.getFuture().get(2, TimeUnit.SECONDS);
            queued.getFuture().get(2, TimeUnit.SECONDS);
            expanded.getFuture().get(2, TimeUnit.SECONDS);
            assertEquals(3, executions.get());
            assertEquals(2, pool.getWorkerCount());
        } finally {
            pool.shutdown();
        }
    }

    private JudgeTask blockingTask(CountDownLatch started, CountDownLatch release,
                                   AtomicInteger executions) {
        return new JudgeTask(1L, "class Main {}", List.of(""), id -> {
            executions.incrementAndGet();
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return SandBoxExecuteResult.success(CodeRunStatus.SUCCEED, List.of(), 0L, 0L);
        }, null);
    }
}
