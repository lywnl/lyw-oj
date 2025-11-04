package com.lyw.system.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;

@Getter
@Setter
public class ExamQuestionAddDTO {
    private Long examId;

    private LinkedHashSet<Long> questionIdSet;
}
