package com.lyw.api.service;

import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.domain.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@FeignClient(contextId = "RemoteJudgeService", value = Constants.JUDGE_SERVICE)
public interface RemoteJudgeService {

    @PostMapping("/judge/doJudgeJavaCode")
    Result<UserQuestionResultVO> doJudgeJavaCode(@RequestBody JudgeSubmitDTO judgeSubmitDTO);

}