package com.lyw.judge.config;

import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.judge.domain.JudgeTask;
import com.lyw.judge.domain.JudgeTaskState;
import com.lyw.judge.domain.SandBoxExecuteResult;
import com.lyw.judge.domain.SandboxTaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(90)
class DockerSandBoxPoolIntegrationTest {
    @Test
    void realDockerPoolConsumesTasksAndHonorsCapacity() throws Exception {
        try (SandboxDockerTestSupport fixture = new SandboxDockerTestSupport(SandboxLimits.defaults(), 1, 2, 2, 200)) {
            fixture.pool.initDockerPool();
            fixture.event("实际资源限制", fixture.inspect(fixture.ids().get(0)));
            JudgeTask first = task();
            JudgeTask second = task();
            assertTrue(fixture.pool.submit(first));
            assertTrue(fixture.pool.submit(second));
            assertEquals(CodeRunStatus.SUCCEED, first.getFuture().get(10, TimeUnit.SECONDS).getRunStatus());
            assertEquals(CodeRunStatus.SUCCEED, second.getFuture().get(10, TimeUnit.SECONDS).getRunStatus());
            assertTrue(fixture.pool.getAllocatedContainerCount() <= 2);
            fixture.assertAdditionWorks();
        }
    }

    @Test
    void realDockerPoolRejectsWhenQueueAndWorkersAreFull() throws Exception {
        try (SandboxDockerTestSupport fixture = new SandboxDockerTestSupport(SandboxLimits.defaults(), 1, 1, 1, 200)) {
            fixture.pool.initDockerPool();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            JudgeTask first = blocking(started, release);
            try {
                assertTrue(fixture.pool.submit(first));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                JudgeTask queued = task();
                JudgeTask rejected = task();
                assertTrue(fixture.pool.submit(queued));
                assertFalse(fixture.pool.submit(rejected));
                assertEquals(JudgeTaskState.REJECTED, rejected.getState());
                assertEquals(CodeRunStatus.SYSTEM_BUSY, rejected.getFuture().get(1, TimeUnit.SECONDS).getRunStatus());
                release.countDown();
                first.getFuture().get(10, TimeUnit.SECONDS);
                queued.getFuture().get(10, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void realDockerPoolReclaimsIdleTemporaryWorker() throws Exception {
        try (SandboxDockerTestSupport fixture = new SandboxDockerTestSupport(SandboxLimits.defaults(), 1, 2, 1, 200)) {
            fixture.pool.initDockerPool();
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            JudgeTask first = blocking(started, release);
            try {
                assertTrue(fixture.pool.submit(first));
                assertTrue(started.await(5, TimeUnit.SECONDS));
                JudgeTask queued = task();
                JudgeTask expanded = task();
                assertTrue(fixture.pool.submit(queued));
                assertTrue(fixture.pool.submit(expanded));
                release.countDown();
                for (JudgeTask job : List.of(first, queued, expanded)) job.getFuture().get(10, TimeUnit.SECONDS);
                SandboxDockerTestSupport.await(() -> fixture.pool.getAllocatedContainerCount() == 1, 10000);
                assertEquals(1, fixture.pool.getWorkerCount());
                fixture.assertAdditionWorks();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void realDockerPoolRebuildsCoreWorkerAfterContainerFailure() throws Exception {
        try (SandboxDockerTestSupport fixture = new SandboxDockerTestSupport(SandboxLimits.defaults(), 1, 1, 2, 200)) {
            fixture.pool.initDockerPool();
            String old = fixture.ids().get(0);
            fixture.control.removeContainerCmd(old).withForce(true).exec();
            JudgeTask failed = task();
            assertTrue(fixture.pool.submit(failed));
            assertEquals(CodeRunStatus.UNKNOWN_FAILED, failed.getFuture().get(15, TimeUnit.SECONDS).getRunStatus());
            fixture.awaitWorkers(1);
            fixture.assertAbsent(old);
            fixture.assertAdditionWorks();
        }
    }

    @Test
    void realDockerPoolReusesHealthyContainerAfterUserFailure() throws Exception {
        try (SandboxDockerTestSupport fixture = new SandboxDockerTestSupport(SandboxLimits.defaults(), 1, 1, 2, 200)) {
            fixture.pool.initDockerPool();
            String id = fixture.ids().get(0);
            SandBoxExecuteResult result = fixture.execute("普通编译错误", "public class Solution { invalid }", List.of(""), 1000);
            assertEquals(CodeRunStatus.COMPILE_FAILED, result.getRunStatus());
            assertEquals(List.of(id), fixture.ids());
            fixture.assertAdditionWorks();
        }
    }

    @Test
    void realDockerPoolRebuildsAfterWorkerExecutionException() throws Exception {
        try (SandboxDockerTestSupport fixture = new SandboxDockerTestSupport(SandboxLimits.defaults(), 1, 1, 2, 200)) {
            fixture.pool.initDockerPool();
            String id = fixture.ids().get(0);
            JudgeTask failed = new JudgeTask(1L, "", List.of(""), container -> {
                throw new IllegalStateException("执行故障注入");
            }, null);
            assertTrue(fixture.pool.submit(failed));
            assertEquals(CodeRunStatus.UNKNOWN_FAILED, failed.getFuture().get(15, TimeUnit.SECONDS).getRunStatus());
            fixture.assertAbsent(id);
            fixture.assertAdditionWorks();
        }
    }

    @Test
    void realDockerPoolFailsStartupWhenCoreContainerCannotBeCreated() throws Exception {
        SandboxLimits invalidForDocker = new SandboxLimits(1, 1, 1, 128, 10000, 5000, 1000, 1048576, 10000);
        try (SandboxDockerTestSupport fixture = new SandboxDockerTestSupport(invalidForDocker, 1, 1, 1, 200)) {
            assertThrows(IllegalStateException.class, fixture.pool::initDockerPool);
            assertEquals(0, fixture.pool.getWorkerCount());
            assertEquals(0, fixture.pool.getAllocatedContainerCount());
            assertTrue(fixture.ids().isEmpty());
            JudgeTask task = task();
            assertFalse(fixture.pool.submit(task));
            assertEquals(CodeRunStatus.SYSTEM_BUSY, task.getFuture().get(1, TimeUnit.SECONDS).getRunStatus());
        }
    }

    private JudgeTask task() {
        return new JudgeTask(1L, "", List.of(""), id -> success(), null);
    }

    private JudgeTask blocking(CountDownLatch started, CountDownLatch release) {
        return new JudgeTask(1L, "", List.of(""), id -> {
            started.countDown();
            try {
                if (!release.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("未释放测试任务");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return success();
        }, null);
    }

    private static SandboxTaskOutcome success() {
        return SandboxTaskOutcome.reusable(SandBoxExecuteResult.success(CodeRunStatus.SUCCEED, List.of(), 0L, 0L));
    }
}
