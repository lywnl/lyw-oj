package com.lyw.common.core.enums;

import lombok.Getter;

@Getter
public enum ProgramType {
    JAVA(0, "java"),
    CPP(1, "cpp"),
    GOLONG(2, "golong");

    private Integer value; //0代表java 1代表CPP 2代表golong

    private String desc;

    ProgramType(Integer value, String desc) {
        this.value = value;
        this.desc = desc;
    }

}
