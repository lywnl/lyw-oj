package com.lyw.judge.domain;

import com.lyw.common.core.enums.CodeRunStatus;

import java.util.Objects;

/** 业务结果与容器是否仍可使用分开传递，不进入公共响应模型。 */
public record SandboxTaskOutcome(SandBoxExecuteResult result, boolean discardContainer, String reason) {
    public SandboxTaskOutcome {
        Objects.requireNonNull(result, "执行结果不能为空");
        Objects.requireNonNull(result.getRunStatus(), "执行状态不能为空");
    }

    public static SandboxTaskOutcome reusable(SandBoxExecuteResult result) {
        return new SandboxTaskOutcome(result, false, "");
    }

    public static SandboxTaskOutcome discard(SandBoxExecuteResult result, String reason) {
        return new SandboxTaskOutcome(result, true, reason);
    }

    public static SandboxTaskOutcome systemFailure(String reason) {
        return discard(SandBoxExecuteResult.fail(CodeRunStatus.UNKNOWN_FAILED), reason);
    }
}
