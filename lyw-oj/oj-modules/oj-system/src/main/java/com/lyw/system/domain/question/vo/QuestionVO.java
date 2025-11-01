package com.lyw.system.domain.question.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuestionVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long questionId;
    private String title;
    private Integer difficulty;
    private String createName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private String createTime;
}
