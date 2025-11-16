package com.lyw.friend.domain.user.vo;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class UserQuestionResultVO {

    private Integer result; //0表示未通过 1表示通过

    private String errMsg;  //错误信息

    private List<UserExeResult> userExeResultList;

}
