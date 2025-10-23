package com.lyw.system.controller;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginResult {
    private int code; //0 表示失败 1表示成功
    private String msg; //返回信息
}
