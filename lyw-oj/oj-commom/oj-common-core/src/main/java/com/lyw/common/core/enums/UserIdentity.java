package com.lyw.common.core.enums;

import lombok.Getter;

@Getter
public enum UserIdentity {
    USER(1, "普通用户"),
    ADMIN(2, "管理员");

    private Integer code;
    private String des;

    UserIdentity(Integer code, String des) {
        this.code = code;
        this.des = des;
    }
}
