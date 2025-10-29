package com.lyw.system.domain.question.dto;

import com.lyw.common.core.domain.dto.PageQueryDTO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuestionQueryDTO extends PageQueryDTO {
    private Integer difficulty;

    private String title;

}
