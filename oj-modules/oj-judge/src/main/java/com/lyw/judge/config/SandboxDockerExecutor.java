package com.lyw.judge.config;

import com.github.dockerjava.api.DockerClient;
import com.lyw.judge.callback.SandboxOutputBudget;
import com.lyw.judge.callback.SandboxOutputCallback;
import com.lyw.judge.callback.SandboxStatisticsCallback;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** 共享执行边界：有界启动、有界输出等待、退出码核验及流关闭。 */
public final class SandboxDockerExecutor {
    private final DockerClient control;
    private final SandboxLimits limits;

    public SandboxDockerExecutor(DockerClient control, SandboxLimits limits) {
        this.control = control;
        this.limits = limits;
    }

    public Result run(DockerClient streams, String containerId, String[] command, long executionTimeoutMs,
                      SandboxOutputBudget budget, boolean collectStatistics) throws IOException, InterruptedException {
        SandboxLimits.checkedNanos(executionTimeoutMs);
        String execId = control.execCreateCmd(containerId).withCmd(command)
                .withAttachStdout(true).withAttachStderr(true).withAttachStdin(false).withTty(false).exec().getId();
        if (execId == null || execId.isBlank()) throw new IOException("Docker 未返回执行 ID");
        try (SandboxStatisticsCallback statistics = collectStatistics ? new SandboxStatisticsCallback() : null;
             SandboxOutputCallback output = new SandboxOutputCallback(budget)) {
            if (statistics != null) {
                streams.statsCmd(containerId).exec(statistics);
                if (!statistics.awaitStarted(limits.cleanupTimeoutMs())) {
                    throw new IOException("Docker 统计流启动失败或超时", statistics.getError());
                }
            }
            long started = System.nanoTime();
            // HttpClient5 的流请求由 SDK 异步启动，无响应头时由 Worker 关闭专属客户端。
            streams.execStartCmd(execId).exec(output);
            if (!output.awaitStarted(limits.cleanupTimeoutMs())) {
                throw new IOException("Docker 执行流启动失败或超时", output.getError());
            }
            boolean finished = output.awaitCompletion(executionTimeoutMs);
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            long memory = statistics == null ? 0L : statistics.getMaxMemory();
            if (budget.isExceeded()) {
                return new Result(-1, output.stdout(), output.stderr(), false, true, elapsed, memory);
            }
            if (output.getError() != null || (statistics != null && statistics.getError() != null)) {
                throw new IOException("Docker 输出或统计流中断", output.getError() != null
                        ? output.getError() : statistics.getError());
            }
            if (!finished) return new Result(-1, output.stdout(), output.stderr(), true, false, elapsed, memory);
            if (!output.completedNormally()) throw new IOException("Docker 执行流未正常完成");
            var execution = control.inspectExecCmd(execId).exec();
            if (!Boolean.FALSE.equals(execution.isRunning()) || execution.getExitCodeLong() == null) {
                throw new IOException("无法确认 Docker 命令已经退出");
            }
            return new Result(execution.getExitCodeLong(), output.stdout(), output.stderr(), false, false, elapsed, memory);
        }
    }

    public record Result(long exitCode, String stdout, String stderr, boolean timedOut,
                         boolean outputExceeded, long useTimeMs, long maxMemory) { }
}
