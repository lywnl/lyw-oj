package com.lyw.system.domain.question.vo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuestionVO {
    private Long questionId;
    private String title;
    private Integer difficulty;
    private String createName;
    private String createTime;
}
