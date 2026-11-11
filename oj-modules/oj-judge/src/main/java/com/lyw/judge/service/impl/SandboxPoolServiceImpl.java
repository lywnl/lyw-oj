package com.lyw.judge.service.impl;

import com.github.dockerjava.api.DockerClient;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.judge.callback.SandboxOutputBudget;
import com.lyw.judge.config.DockerSandBoxPool;
import com.lyw.judge.config.SandboxDockerExecutor;
import com.lyw.judge.domain.JudgeTask;
import com.lyw.judge.domain.SandBoxExecuteResult;
import com.lyw.judge.domain.SandboxTaskOutcome;
import com.lyw.judge.service.ISandboxPoolService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;

@Service
@Slf4j
public class SandboxPoolServiceImpl implements ISandboxPoolService {
    private static final int MAX_DIAGNOSTIC_CHARACTERS = 500;
    private final DockerSandBoxPool sandBoxPool;

    public SandboxPoolServiceImpl(DockerSandBoxPool sandBoxPool) {
        this.sandBoxPool = sandBoxPool;
    }

    @Override
    public SandBoxExecuteResult exeJavaCode(Long userId, String userCode, List<String> inputList, Long questionTimeLimitMs) {
        final long timeoutMs;
        try {
            timeoutMs = sandBoxPool.getLimits().runtimeTimeoutMs(questionTimeLimitMs);
            if (userCode == null || inputList == null || inputList.stream().anyMatch(value -> value == null)) {
                return SandBoxExecuteResult.fail(CodeRunStatus.UNKNOWN_FAILED);
            }
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            return SandBoxExecuteResult.fail(CodeRunStatus.UNKNOWN_FAILED);
        }
        JudgeTask task = new JudgeTask(userId, userCode, inputList,
                id -> executeInContainer(id, userCode, inputList, timeoutMs), null);
        if (!sandBoxPool.submit(task)) return SandBoxExecuteResult.fail(CodeRunStatus.SYSTEM_BUSY);
        try {
            return task.getFuture().get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待沙箱结果被中断", interrupted);
        } catch (ExecutionException error) {
            throw new IllegalStateException("沙箱执行失败", error.getCause());
        }
    }

    private SandboxTaskOutcome executeInContainer(String id, String source, List<String> inputs, long runtimeTimeoutMs) {
        SandboxOutputBudget budget = new SandboxOutputBudget(sandBoxPool.getLimits().outputMaxBytes());
        SandboxDockerExecutor executor = sandBoxPool.getExecutor();
        DockerClient streams = sandBoxPool.getStreamClient(id);
        String volume = sandBoxPool.getVolumeDir();
        try {
            sandBoxPool.writeSource(id, source);
            SandboxDockerExecutor.Result compilation = executor.run(streams, id,
                    new String[]{"javac", "-encoding", "UTF-8", volume + "/Solution.java"},
                    sandBoxPool.getLimits().compileTimeoutMs(), budget, false);
            SandboxTaskOutcome interruption = interrupted(compilation, true);
            if (interruption != null) return interruption;
            if (compilation.exitCode() != 0) {
                return SandboxTaskOutcome.reusable(SandBoxExecuteResult.fail(CodeRunStatus.COMPILE_FAILED,
                        diagnostic(compilation.stderr(), "编译失败")));
            }

            List<String> outputs = new ArrayList<>();
            long maxMemory = 0L;
            long maxTime = 0L;
            for (String input : inputs) {
                List<String> command = new ArrayList<>(List.of("java", "-Dfile.encoding=UTF-8", "-cp", volume, "Solution"));
                if (!input.isEmpty()) command.addAll(Arrays.asList(input.split(" ")));
                SandboxDockerExecutor.Result execution = executor.run(streams, id, command.toArray(String[]::new),
                        runtimeTimeoutMs, budget, true);
                interruption = interrupted(execution, false);
                if (interruption != null) return interruption;
                if (execution.exitCode() != 0) {
                    return SandboxTaskOutcome.reusable(SandBoxExecuteResult.fail(CodeRunStatus.NOT_ALL_PASSED,
                            diagnostic(execution.stderr(), "程序运行失败")));
                }
                outputs.add(execution.stdout().trim());
                maxMemory = Math.max(maxMemory, execution.maxMemory());
                maxTime = Math.max(maxTime, execution.useTimeMs());
            }
            return SandboxTaskOutcome.reusable(SandBoxExecuteResult.success(CodeRunStatus.SUCCEED, outputs, maxMemory, maxTime));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return SandboxTaskOutcome.systemFailure("命令启动或等待被中断");
        } catch (Exception error) {
            log.warn("池化执行系统异常，containerId={}，原因={}", id, error.toString());
            return SandboxTaskOutcome.systemFailure(error.getClass().getSimpleName());
        }
    }

    private SandboxTaskOutcome interrupted(SandboxDockerExecutor.Result result, boolean compilation) {
        if (result.outputExceeded()) {
            return SandboxTaskOutcome.discard(SandBoxExecuteResult.fail(CodeRunStatus.FAILED, "程序输出超出限制"), "OUTPUT_LIMIT");
        }
        if (result.timedOut()) {
            return SandboxTaskOutcome.discard(SandBoxExecuteResult.fail(CodeRunStatus.OUT_OF_TIME,
                    compilation ? "编译超时" : "运行超时"), compilation ? "COMPILE_TIMEOUT" : "RUN_TIMEOUT");
        }
        return null;
    }

    /** 提交表 exe_message 为 varchar(500)，不能把长栈跟踪变成数据库系统错误。 */
    private String diagnostic(String stderr, String fallback) {
        if (stderr.isBlank()) return fallback;
        if (stderr.codePointCount(0, stderr.length()) <= MAX_DIAGNOSTIC_CHARACTERS) return stderr;
        String suffix = "\n…（诊断已截断）";
        int count = MAX_DIAGNOSTIC_CHARACTERS - suffix.codePointCount(0, suffix.length());
        return stderr.substring(0, stderr.offsetByCodePoints(0, count)) + suffix;
    }
}
