package com.lyw.judge.config;

import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.judge.domain.JudgeTask;
import com.lyw.judge.domain.JudgeTaskState;
import com.lyw.judge.domain.SandBoxExecuteResult;
import com.lyw.judge.domain.SandboxTaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DockerSandBoxPoolConcurrencyTest {
    @TempDir Path temporary;

    @Test
    void substituteClientKeepsSingleExecutionAndCapacityAccounting() throws Exception {
        FakeDocker docker = new FakeDocker();
        SandboxLimits limits = SandboxLimits.defaults();
        DockerSandBoxPool pool = new DockerSandBoxPool(docker.control, docker.factory(), "image", "/usr/share/java",
                limits, 1, 2, 1, 100, "unit-capacity", new SandboxFileManager(temporary.resolve("pool"), 10000));
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        try {
            pool.initDockerPool();
            JudgeTask first = blocking(started, release, executions, active, maximum);
            JudgeTask queued = blocking(started, release, executions, active, maximum);
            JudgeTask expanded = blocking(started, release, executions, active, maximum);
            JudgeTask rejected = blocking(started, release, executions, active, maximum);
            assertTrue(pool.submit(first));
            while (executions.get() == 0) Thread.sleep(5);
            assertTrue(pool.submit(queued));
            assertTrue(pool.submit(expanded));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(pool.submit(rejected));
            assertEquals(JudgeTaskState.REJECTED, rejected.getState());
            assertEquals(CodeRunStatus.SYSTEM_BUSY, rejected.getFuture().get(1, TimeUnit.SECONDS).getRunStatus());
            assertEquals(2, pool.getAllocatedContainerCount());
            assertEquals(2, docker.creates.get());
            release.countDown();
            for (JudgeTask task : List.of(first, queued, expanded)) {
                assertEquals(CodeRunStatus.SUCCEED, task.getFuture().get(5, TimeUnit.SECONDS).getRunStatus());
            }
            assertEquals(3, executions.get());
            assertEquals(2, maximum.get());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (pool.getAllocatedContainerCount() > 1 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(1, pool.getWorkerCount());
            assertEquals(1, pool.getAllocatedContainerCount());
        } finally {
            release.countDown();
            pool.shutdown();
        }
        assertEquals(0, pool.getAllocatedContainerCount());
    }

    private JudgeTask blocking(CountDownLatch started, CountDownLatch release, AtomicInteger executions,
                               AtomicInteger active, AtomicInteger maximum) {
        return new JudgeTask(1L, "source", List.of(""), id -> {
            executions.incrementAndGet();
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("测试未释放任务");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            } finally {
                active.decrementAndGet();
            }
            return SandboxTaskOutcome.reusable(SandBoxExecuteResult.success(CodeRunStatus.SUCCEED, List.of(), 0L, 0L));
        }, null);
    }
}
