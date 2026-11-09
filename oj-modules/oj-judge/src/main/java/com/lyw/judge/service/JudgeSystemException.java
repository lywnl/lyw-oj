package com.lyw.judge.service;

import com.lyw.common.core.enums.CodeRunStatus;

/** 基础设施故障不能作为用户代码的真实判题记录。 */
public class JudgeSystemException extends RuntimeException {
    private final CodeRunStatus status;

    public JudgeSystemException(CodeRunStatus status) {
        super(status.getMsg());
        this.status = status;
    }

    public JudgeSystemException(CodeRunStatus status, Throwable cause) {
        super(status.getMsg(), cause);
        this.status = status;
    }

    public CodeRunStatus getStatus() {
        return status;
    }
}
