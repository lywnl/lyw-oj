package com.lyw.judge.config;

import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.judge.domain.SandBoxExecuteResult;
import com.lyw.judge.service.impl.SandboxPoolServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SandboxExecutionTest {
    @TempDir Path temporary;
    final FakeDocker docker = new FakeDocker();
    DockerSandBoxPool pool;
    SandboxPoolServiceImpl service;

    @AfterEach void close() { if (pool != null) pool.shutdown(); }

    private void initialize(long compileMs, long outputLimit) {
        SandboxLimits limits = new SandboxLimits(100000000, 100000000, 1, 128, compileMs, 100, 10, outputLimit, 1000);
        pool = new DockerSandBoxPool(docker.control, docker.factory(), "image", "/usr/share/java", limits,
                1, 1, 2, 100, "unit-execution", new SandboxFileManager(temporary.resolve("pool"), 1000));
        pool.initDockerPool();
        service = new SandboxPoolServiceImpl(pool);
    }

    @Test
    void exitCodeDeterminesResultAndAllOutputFramesAreKept() {
        initialize(1000, 1048576);
        docker.onExecution = execution -> {
            execution.callback.onNext(new Frame(StreamType.STDERR, "诊断".getBytes(StandardCharsets.UTF_8)));
            if ("java".equals(execution.words[0])) {
                execution.callback.onNext(new Frame(StreamType.STDOUT, new byte[]{'1'}));
                execution.callback.onNext(new Frame(StreamType.STDOUT, new byte[]{'2', '\n'}));
            }
            execution.finish(0L);
        };
        SandBoxExecuteResult result = service.exeJavaCode(1L, "source", List.of(""), 1000L);
        assertEquals(CodeRunStatus.SUCCEED, result.getRunStatus());
        assertEquals(List.of("12"), result.getOutputList());
        assertEquals(1, docker.creates.get());
        assertEquals(4, docker.streamsClosed.get()); // 准入、编译、运行输出及 stats。
    }

    @Test
    void longFailureDiagnosticsFitExistingDatabaseColumnWithoutSplittingUnicode() {
        initialize(1000, 1048576);
        docker.onExecution = execution -> {
            execution.callback.onNext(new Frame(StreamType.STDERR, "异常🙂".repeat(1000).getBytes(StandardCharsets.UTF_8)));
            execution.finish(1L);
        };
        SandBoxExecuteResult result = service.exeJavaCode(1L, "source", List.of(""), 1000L);
        assertEquals(CodeRunStatus.COMPILE_FAILED, result.getRunStatus());
        assertEquals(500, result.getExeMessage().codePointCount(0, result.getExeMessage().length()));
        assertTrue(result.getExeMessage().endsWith("（诊断已截断）"));
        assertEquals(result.getExeMessage(), new String(result.getExeMessage().getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertEquals(1, docker.creates.get());
    }

    @Test
    void compilationTimeoutIsDistinctFromOrdinaryCompilationFailure() {
        initialize(30, 1048576);
        docker.onExecution = execution -> { /* 故意不完成命令，只测试等待与回收逻辑。 */ };
        SandBoxExecuteResult result = service.exeJavaCode(1L, "source", List.of(""), 1000L);
        assertEquals(CodeRunStatus.OUT_OF_TIME, result.getRunStatus());
        assertEquals("编译超时", result.getExeMessage());
        assertFalse(docker.containers.containsKey("container-1"));
    }

    @Test
    void outputLimitIsSharedAndForcesRetirement() {
        initialize(1000, 3);
        docker.onExecution = execution -> {
            execution.callback.onNext(new Frame(StreamType.STDOUT, new byte[]{'a', 'b', 'c', 'd'}));
            execution.finish(0L);
        };
        SandBoxExecuteResult result = service.exeJavaCode(1L, "source", List.of(""), 1000L);
        assertEquals(CodeRunStatus.FAILED, result.getRunStatus());
        assertEquals("程序输出超出限制", result.getExeMessage());
        assertFalse(docker.containers.containsKey("container-1"));
    }

    @Test
    void missingExitStateCannotBeReportedAsUserFailureOrSuccess() {
        initialize(1000, 1048576);
        docker.onExecution = execution -> execution.callback.onComplete();
        assertEquals(CodeRunStatus.UNKNOWN_FAILED, service.exeJavaCode(1L, "source", List.of(""), 1000L).getRunStatus());
        assertFalse(docker.containers.containsKey("container-1"));
    }

    @Test
    void invalidQuestionLimitsNeverEnterExecution() {
        initialize(1000, 1048576);
        int initialCommands = docker.executions.size();
        for (Long limit : new Long[]{null, -1L, 0L, Long.MAX_VALUE}) {
            assertEquals(CodeRunStatus.UNKNOWN_FAILED, service.exeJavaCode(1L, "source", List.of(""), limit).getRunStatus());
        }
        assertEquals(initialCommands, docker.executions.size());
    }
}
