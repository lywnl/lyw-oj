package com.lyw.friend.domain.exam.vo;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExamRankVO {

    private Long userId;

    private String nickName;

    private Integer examRank;

    private Integer score;
}
