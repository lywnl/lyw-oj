package com.lyw.common.core.enums;

import lombok.Getter;

@Getter
public enum QuestionResult {
    FAIL(0),
    PASS(1),;

    private Integer value;

    QuestionResult(Integer value) {
        this.value = value;
    }
}
