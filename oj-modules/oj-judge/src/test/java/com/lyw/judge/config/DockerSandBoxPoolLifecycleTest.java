package com.lyw.judge.config;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.judge.domain.JudgeTask;
import com.lyw.judge.domain.SandBoxExecuteResult;
import com.lyw.judge.domain.SandboxTaskOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class DockerSandBoxPoolLifecycleTest {
    @TempDir Path temporary;
    private final FakeDocker docker = new FakeDocker();
    private DockerSandBoxPool pool;

    @AfterEach void close() { if (pool != null) pool.shutdown(); }

    private void initialize() {
        pool = new DockerSandBoxPool(docker.control, docker.factory(), "image", "/usr/share/java",
                SandboxLimits.defaults(), 1, 1, 2, 100, "unit-lifecycle",
                new SandboxFileManager(temporary.resolve("pool"), 10000));
        pool.initDockerPool();
    }

    @Test
    void normalFailureCleansFilesAndKeepsContainer() throws Exception {
        initialize();
        FakeDocker.Box original = docker.containers.values().iterator().next();
        Files.writeString(original.directory.resolve("Solution.class"), "old");
        Files.writeString(original.directory.resolve("Solution$Inner.class"), "old");
        JudgeTask task = task(SandboxTaskOutcome.reusable(SandBoxExecuteResult.fail(CodeRunStatus.COMPILE_FAILED)));
        assertTrue(pool.submit(task));
        assertEquals(CodeRunStatus.COMPILE_FAILED, task.getFuture().get(5, TimeUnit.SECONDS).getRunStatus());
        assertTrue(docker.containers.containsKey(original.id));
        try (var entries = Files.list(original.directory)) { assertEquals(0, entries.count()); }
        assertEquals(0, docker.removes.get());
        assertEquals(1, docker.creates.get());
    }

    @Test
    void retirementAndDirectoryRemovalPrecedeFutureCompletion() throws Exception {
        initialize();
        FakeDocker.Box original = docker.containers.values().iterator().next();
        JudgeTask task = task(timeout());
        AtomicBoolean removalObserved = new AtomicBoolean();
        docker.beforeRemove = () -> {
            if (removalObserved.compareAndSet(false, true)) assertFalse(task.getFuture().isDone());
        };
        var verified = task.getFuture().thenAccept(result -> {
            assertFalse(docker.containers.containsKey(original.id));
            assertFalse(Files.exists(original.directory));
            docker.events.add("future:" + original.id);
        });
        assertTrue(pool.submit(task));
        assertEquals(CodeRunStatus.OUT_OF_TIME, task.getFuture().get(5, TimeUnit.SECONDS).getRunStatus());
        verified.get(5, TimeUnit.SECONDS);
        await(() -> pool.getWorkerCount() == 1 && docker.creates.get() == 2);
        assertTrue(docker.events.indexOf("remove:" + original.id) < docker.events.indexOf("future:" + original.id));
        assertEquals(1, pool.getAllocatedContainerCount());
        JudgeTask next = task(success());
        assertTrue(pool.submit(next));
        assertEquals(CodeRunStatus.SUCCEED, next.getFuture().get(5, TimeUnit.SECONDS).getRunStatus());
    }

    @Test
    void removalFailurePreservesSlotAndDoesNotRebuild() throws Exception {
        initialize();
        docker.failRemoval = true;
        JudgeTask task = task(timeout());
        assertTrue(pool.submit(task));
        assertEquals(CodeRunStatus.UNKNOWN_FAILED, task.getFuture().get(5, TimeUnit.SECONDS).getRunStatus());
        assertEquals(1, pool.getQuarantinedContainerCount());
        assertEquals(1, pool.getAllocatedContainerCount());
        assertEquals(1, docker.creates.get());
        JudgeTask next = task(success());
        assertFalse(pool.submit(next));
        assertEquals(CodeRunStatus.SYSTEM_BUSY, next.getFuture().get(1, TimeUnit.SECONDS).getRunStatus());
    }

    @Test
    void successfulRemoveResponseIsNotEnoughWithoutAbsenceConfirmation() throws Exception {
        initialize();
        docker.leaveAfterRemoval = true;
        JudgeTask task = task(timeout());
        assertTrue(pool.submit(task));
        assertEquals(CodeRunStatus.UNKNOWN_FAILED, task.getFuture().get(5, TimeUnit.SECONDS).getRunStatus());
        assertEquals(1, pool.getAllocatedContainerCount());
        assertEquals(1, pool.getQuarantinedContainerCount());
        assertEquals(1, docker.creates.get());
    }

    @Test
    void queuedTasksAreRejectedWhenLastCoreCannotBeRebuilt() throws Exception {
        initialize();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JudgeTask first = new JudgeTask(1L, "source", List.of(""), id -> {
            started.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("未释放测试任务"); }
            catch (InterruptedException error) { throw new IllegalStateException(error); }
            return timeout();
        }, null);
        assertTrue(pool.submit(first));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        JudgeTask queued = task(success());
        assertTrue(pool.submit(queued));
        docker.failCreateAfter = 1;
        release.countDown();
        assertEquals(CodeRunStatus.OUT_OF_TIME, first.getFuture().get(5, TimeUnit.SECONDS).getRunStatus());
        assertEquals(CodeRunStatus.SYSTEM_BUSY, queued.getFuture().get(5, TimeUnit.SECONDS).getRunStatus());
        assertEquals(0, pool.getWorkerCount());
        assertEquals(1, pool.getQuarantinedContainerCount());
    }

    @Test
    void unknownCreationKeepsNameAndDirectoryEvenIfLookupIsNotFound() {
        docker.createNeverMaterializes = true;
        assertThrows(IllegalStateException.class, this::initialize);
        assertEquals(1, pool.getAllocatedContainerCount());
        assertEquals(1, pool.getQuarantinedContainerCount());
        assertTrue(Files.isDirectory(temporary.resolve("pool/unit-lifecycle-0")));
        assertEquals(0, docker.removes.get());
    }

    @Test
    void lostCreateReplyCanOnlyCleanAnExactlyOwnedContainer() {
        docker.loseCreateReply = true;
        assertThrows(IllegalStateException.class, this::initialize);
        assertEquals(0, pool.getAllocatedContainerCount());
        assertEquals(1, docker.removes.get());
        assertTrue(docker.containers.isEmpty());
        assertFalse(Files.exists(temporary.resolve("pool/unit-lifecycle-0")));
    }

    @Test
    void refusesExistingDirectoryWithoutDeletingItsFiles() throws Exception {
        Path old = Files.createDirectories(temporary.resolve("pool/unit-lifecycle-0"));
        Path sentinel = Files.writeString(old.resolve("keep.class"), "旧文件");
        assertThrows(IllegalStateException.class, this::initialize);
        assertEquals("旧文件", Files.readString(sentinel));
        assertEquals(0, docker.creates.get());
        assertEquals(0, docker.removes.get());
    }

    @Test
    void refusesForeignContainerBeforeCreatingAnyResource() throws Exception {
        Path outside = Files.createDirectories(temporary.resolve("foreign"));
        HostConfig config = new HostConfig().withBinds(new Bind(outside.toString(), new Volume("/usr/share/java")));
        docker.containers.put("foreign", new FakeDocker.Box("foreign", "unit-lifecycle-0", config, Map.of("owner", "other")));
        assertThrows(IllegalStateException.class, this::initialize);
        assertTrue(docker.containers.containsKey("foreign"));
        assertEquals(0, docker.creates.get());
        assertEquals(0, docker.removes.get());
    }

    @Test
    void rejectsActualLimitMismatchAndCleansPartialStartup() {
        docker.badLimits = true;
        assertThrows(IllegalStateException.class, this::initialize);
        assertTrue(docker.containers.isEmpty());
        assertEquals(0, pool.getAllocatedContainerCount());
        assertEquals(1, docker.removes.get());
    }

    @Test
    void missingNativeClientLibraryCannotLeakPartiallyCreatedContainer() {
        pool = new DockerSandBoxPool(docker.control, () -> { throw new UnsatisfiedLinkError("缺少原生库"); },
                "image", "/usr/share/java", SandboxLimits.defaults(), 1, 1, 2, 100, "unit-native-failure",
                new SandboxFileManager(temporary.resolve("pool"), 10000));
        assertThrows(IllegalStateException.class, pool::initDockerPool);
        assertTrue(docker.containers.isEmpty());
        assertEquals(0, pool.getAllocatedContainerCount());
        assertEquals(1, docker.removes.get());
    }

    @Test
    void deepUserDirectoryStillCompletesFutureAndKeepsQuarantineSlot() throws Exception {
        initialize();
        JudgeTask task = new JudgeTask(1L, "source", List.of(""), id -> {
            try {
                Path directory = Path.of(pool.getCodeDir(id));
                for (int level = 0; level < 70; level++) directory = Files.createDirectory(directory.resolve("d"));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
            return success();
        }, null);
        assertTrue(pool.submit(task));
        assertEquals(CodeRunStatus.UNKNOWN_FAILED, task.getFuture().get(5, TimeUnit.SECONDS).getRunStatus());
        assertEquals(1, pool.getQuarantinedContainerCount());
        assertEquals(1, pool.getAllocatedContainerCount());
        assertTrue(docker.containers.isEmpty());
        assertEquals(1, docker.creates.get());
    }

    @Test
    void concurrentShutdownAndExecutionRetireOnlyOnce() throws Exception {
        initialize();
        CountDownLatch started = new CountDownLatch(1);
        JudgeTask task = new JudgeTask(1L, "source", List.of(""), id -> {
            started.countDown();
            try { new CountDownLatch(1).await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            return timeout();
        }, null);
        assertTrue(pool.submit(task));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        Thread shutdown = new Thread(pool::shutdown);
        shutdown.start();
        shutdown.join(5000);
        assertFalse(shutdown.isAlive());
        assertEquals(CodeRunStatus.UNKNOWN_FAILED, task.getFuture().get(5, TimeUnit.SECONDS).getRunStatus());
        assertEquals(1, docker.removes.get());
        assertEquals(1, docker.creates.get());
        assertEquals(0, pool.getAllocatedContainerCount());
    }

    @Test
    void anotherRetiringCoreCanStillRecoverQueuedTasks() throws Exception {
        pool = new DockerSandBoxPool(docker.control, docker.factory(), "image", "/usr/share/java",
                SandboxLimits.defaults(), 2, 2, 2, 100, "unit-two-cores",
                new SandboxFileManager(temporary.resolve("pool"), 10000));
        pool.initDockerPool();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch releaseTasks = new CountDownLatch(1);
        CountDownLatch secondRemoving = new CountDownLatch(1);
        CountDownLatch releaseRemoval = new CountDownLatch(1);
        AtomicReference<String> firstId = new AtomicReference<>();
        AtomicReference<String> secondId = new AtomicReference<>();
        JudgeTask first = retiringTask(firstId, started, releaseTasks);
        JudgeTask second = retiringTask(secondId, started, releaseTasks);
        assertTrue(pool.submit(first));
        assertTrue(pool.submit(second));
        assertTrue(started.await(5, TimeUnit.SECONDS));
        JudgeTask queued = task(success());
        assertTrue(pool.submit(queued));
        docker.beforeRemoveId = id -> {
            try {
                if (id.equals(secondId.get())) {
                    secondRemoving.countDown();
                    if (!releaseRemoval.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("未释放删除");
                } else if (id.equals(firstId.get())) {
                    if (!secondRemoving.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("另一个核心未开始回收");
                    throw new IllegalStateException("第一个核心删除失败");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
        };
        try {
            releaseTasks.countDown();
            assertEquals(CodeRunStatus.UNKNOWN_FAILED, first.getFuture().get(5, TimeUnit.SECONDS).getRunStatus());
            assertFalse(queued.getFuture().isDone(), "仍有核心回收中，不应提前拒绝排队任务");
            releaseRemoval.countDown();
            assertEquals(CodeRunStatus.OUT_OF_TIME, second.getFuture().get(5, TimeUnit.SECONDS).getRunStatus());
            assertEquals(CodeRunStatus.SUCCEED, queued.getFuture().get(5, TimeUnit.SECONDS).getRunStatus());
            assertEquals(1, pool.getQuarantinedContainerCount());
            assertEquals(2, pool.getAllocatedContainerCount());
        } finally {
            releaseTasks.countDown(); releaseRemoval.countDown();
            docker.beforeRemoveId = null;
        }
    }

    private JudgeTask retiringTask(AtomicReference<String> id, CountDownLatch started, CountDownLatch release) {
        return new JudgeTask(1L, "source", List.of(""), container -> {
            id.set(container); started.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("未释放执行"); }
            catch (InterruptedException error) { throw new IllegalStateException(error); }
            return timeout();
        }, null);
    }

    private JudgeTask task(SandboxTaskOutcome outcome) {
        return new JudgeTask(1L, "source", List.of(""), id -> outcome, null);
    }
    private SandboxTaskOutcome timeout() {
        return SandboxTaskOutcome.discard(SandBoxExecuteResult.fail(CodeRunStatus.OUT_OF_TIME, "运行超时"), "RUN_TIMEOUT");
    }
    private SandboxTaskOutcome success() {
        return SandboxTaskOutcome.reusable(SandBoxExecuteResult.success(CodeRunStatus.SUCCEED, List.of(), 0L, 0L));
    }
    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "状态未在期限内收敛");
    }
}
