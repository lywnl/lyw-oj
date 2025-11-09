package com.lyw.friend.domain.exam.dto;

import com.lyw.common.core.domain.dto.PageQueryDTO;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
public class ExamQueryDTO extends PageQueryDTO {
    private String title;

    private String  startTime;

    private String  endTime;

    private Integer type;
}
