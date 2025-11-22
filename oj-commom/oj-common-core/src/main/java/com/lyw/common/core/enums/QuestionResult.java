package com.lyw.common.core.enums;

import lombok.Getter;

@Getter
public enum QuestionResult {
    FAIL(0), // 未通过
    PASS(1), // 通过
    NOT_SUBMIT(2), // 未提交
    IN_JUDGE(3); // 正在判题中


    private Integer value;

    QuestionResult(Integer value) {
        this.value = value;
    }
}
