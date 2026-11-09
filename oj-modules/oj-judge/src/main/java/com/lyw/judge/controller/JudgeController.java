package com.lyw.judge.controller;

import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.dto.JudgeSubmissionIdentity;
import com.lyw.api.domain.cache.JudgeResultCache;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.redis.service.StateCacheService;
import com.lyw.judge.service.IJudgeService;
import com.lyw.judge.service.JudgeSystemException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/judge")
@Slf4j
public class JudgeController extends BaseController {

    @Autowired
    private IJudgeService judgeService;

    @Autowired
    private StateCacheService stateCacheService;

    @PostMapping("/doJudgeJavaCode")
    public Result<UserQuestionResultVO> doJudgeJavaCode(@RequestBody JudgeSubmitDTO judgeSubmitDTO) {
        if (!JudgeSubmissionIdentity.isValid(judgeSubmitDTO)) return Result.fail(ResultCode.FAILED_PARAMS_VALIDATE);
        try {
            return Result.ok(judgeService.doJudgeJavaCode(judgeSubmitDTO));
        } catch (Exception failure) {
            String message = failure instanceof JudgeSystemException system
                    ? system.getStatus().getMsg() : CodeRunStatus.UNKNOWN_FAILED.getMsg();
            try {
                stateCacheService.update(CacheConstants.getJudgeResultKey(judgeSubmitDTO.getUserId(), judgeSubmitDTO.getSubmitId()),
                        JudgeResultCache.error(judgeSubmitDTO.getUserId(), judgeSubmitDTO.getSubmitId(), message),
                        CacheConstants.JUDGE_RESULT_TTL, TimeUnit.MINUTES);
            } catch (Exception cacheFailure) {
                log.warn("同步判题提示缓存失败，submitId={}", judgeSubmitDTO.getSubmitId());
            }
            return Result.ok(UserQuestionResultVO.systemError(judgeSubmitDTO.getSubmitId(), message));
        }
    }
}
