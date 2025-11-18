package com.lyw.judge.service;

import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.vo.UserQuestionResultVO;

public interface IJudgeService {
    UserQuestionResultVO doJudgeJavaCode(JudgeSubmitDTO judgeSubmitDTO);

}
