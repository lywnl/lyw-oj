package com.lyw.common.file.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OSSResult {

    private String name;

    /**
     * 对象状态：true成功，false失败
     */
    private boolean success;
}