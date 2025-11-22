package com.lyw.common.core.enums;

import lombok.Getter;

@Getter
public enum ExamListType {

    EXAM_UN_FINISH_LIST(0), // 未完成列表

    EXAM_HISTORY_LIST(1), // 历史列表

    USER_EXAM_LIST(2);  //用户竞赛列表

    private final Integer value;

    ExamListType(Integer value) {
        this.value = value;
    }
}