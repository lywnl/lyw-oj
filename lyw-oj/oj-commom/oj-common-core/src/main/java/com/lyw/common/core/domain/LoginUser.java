package com.lyw.common.core.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginUser {
    private String nickname;

    Integer identity; // 1:普通用户 2:管理员

    private String headImage;  //头像
}
