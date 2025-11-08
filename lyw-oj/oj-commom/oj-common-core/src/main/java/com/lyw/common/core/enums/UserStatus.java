package com.lyw.common.core.enums;

import lombok.Getter;

@Getter
public enum UserStatus {
    BLOCK(0),

    NORMAL(1);

    private Integer value; //用户状态0表示拉黑 1表示正常


    UserStatus(Integer value) {
        this.value = value;
    }
}
