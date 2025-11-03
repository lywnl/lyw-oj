package com.lyw.system.domain.exam.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;

@Getter
@Setter
public class ExamQuestionDTO {
    private Long examId;

    private LinkedHashSet<Long> questionIds;
}
