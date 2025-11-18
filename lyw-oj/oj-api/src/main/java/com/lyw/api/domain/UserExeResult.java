package com.lyw.api.domain;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserExeResult {

    private String input;

    private String output;   //期望输出

    private String exeOutput; //实际输出

}