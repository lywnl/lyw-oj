package com.lyw.friend.domain.question.dto;

import com.lyw.common.core.domain.dto.PageQueryDTO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuestionQueryDTO extends PageQueryDTO {

    private String keyword;

    private Integer difficulty; //1:简单 2:中等 3:困难
}
