package com.lyw.api.domain.enums;

/** 查询状态与程序是否通过分开，系统错误不是一次真实判题结果。 */
public enum JudgeResultState {
    JUDGED,
    SYSTEM_ERROR,
    NO_RESULT
}
