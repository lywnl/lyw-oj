package com.lyw.api.domain.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.lyw.api.domain.UserExeResult;
import com.lyw.api.domain.enums.JudgeResultState;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class UserQuestionResultVO {
    @JsonSerialize(using = ToStringSerializer.class)
    private Long submitId;

    private JudgeResultState resultState;

    private Integer pass; // 仅真实结果使用 0/1，其余状态为 null。

    private String exeMessage; //异常信息

    private List<UserExeResult> userExeResultList;

    @JsonIgnore
    private Integer score;

    public static UserQuestionResultVO noResult(Long submitId) {
        UserQuestionResultVO result = new UserQuestionResultVO();
        result.setSubmitId(submitId);
        result.setResultState(JudgeResultState.NO_RESULT);
        result.setExeMessage("暂未取得判题结果");
        return result;
    }

    public static UserQuestionResultVO systemError(Long submitId, String message) {
        UserQuestionResultVO result = noResult(submitId);
        result.setResultState(JudgeResultState.SYSTEM_ERROR);
        result.setExeMessage(message);
        return result;
    }
}
