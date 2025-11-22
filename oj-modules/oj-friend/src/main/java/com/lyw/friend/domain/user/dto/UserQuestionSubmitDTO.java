package com.lyw.friend.domain.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserQuestionSubmitDTO {

    private Long examId;

    private Long questionId;

    private Integer programType;

    private String userCode;

}
