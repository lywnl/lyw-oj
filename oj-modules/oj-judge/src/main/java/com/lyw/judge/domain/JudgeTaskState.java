package com.lyw.judge.domain;

/**
 * 判题任务生命周期状态。
 */
public enum JudgeTaskState {

    CREATED,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    REJECTED
}
