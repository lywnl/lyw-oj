package com.lyw.common.core.domain.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PageQueryDTO {
    private Integer pageSize = 10; //每页的大小

    private Integer pageNum = 1;  //第几页
}
