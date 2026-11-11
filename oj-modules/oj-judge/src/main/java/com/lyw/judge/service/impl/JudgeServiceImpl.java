package com.lyw.judge.service.impl;

import com.lyw.api.domain.UserExeResult;
import com.lyw.api.domain.cache.JudgeResultCache;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.dto.JudgeSubmissionIdentity;
import com.lyw.api.domain.enums.JudgeResultState;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.constants.JudgeConstants;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.common.redis.service.StateCacheService;
import com.lyw.judge.domain.SandBoxExecuteResult;
import com.lyw.judge.domain.UserSubmit;
import com.lyw.judge.service.IJudgeService;
import com.lyw.judge.service.ISandboxPoolService;
import com.lyw.judge.service.JudgeSystemException;
import com.lyw.judge.service.UserSubmitStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class JudgeServiceImpl implements IJudgeService {

    @Autowired
    private ISandboxPoolService sandboxPoolService;

    @Autowired
    private UserSubmitStore userSubmitStore;

    @Autowired
    private StateCacheService stateCacheService;

    @Override
    public UserQuestionResultVO doJudgeJavaCode(JudgeSubmitDTO judgeSubmitDTO) {
        if (!JudgeSubmissionIdentity.isValid(judgeSubmitDTO)) {
            throw new IllegalArgumentException("判题消息缺少有效提交身份");
        }
        UserSubmit existing = userSubmitStore.findCompleted(judgeSubmitDTO);
        if (existing != null) return returnSaved(judgeSubmitDTO, existing);
        if (judgeSubmitDTO.getUserCode() == null || judgeSubmitDTO.getInputList() == null
                || judgeSubmitDTO.getOutputList() == null
                || judgeSubmitDTO.getInputList().size() != judgeSubmitDTO.getOutputList().size()) {
            throw new JudgeSystemException(CodeRunStatus.UNKNOWN_FAILED);
        }
        SandBoxExecuteResult sandBoxExecuteResult;
        try {
            sandBoxExecuteResult = sandboxPoolService.exeJavaCode(judgeSubmitDTO.getUserId(),
                    judgeSubmitDTO.getUserCode(), judgeSubmitDTO.getInputList(), judgeSubmitDTO.getTimeLimit());
        } catch (Exception e) {
            throw new JudgeSystemException(CodeRunStatus.UNKNOWN_FAILED, e);
        }
        if (sandBoxExecuteResult == null || sandBoxExecuteResult.getRunStatus() == null) {
            throw new JudgeSystemException(CodeRunStatus.UNKNOWN_FAILED);
        }
        CodeRunStatus status = sandBoxExecuteResult.getRunStatus();
        if (status == CodeRunStatus.SYSTEM_BUSY || status == CodeRunStatus.UNKNOWN_FAILED
                || status == CodeRunStatus.RUNNING) {
            throw new JudgeSystemException(status == CodeRunStatus.SYSTEM_BUSY
                    ? CodeRunStatus.SYSTEM_BUSY : CodeRunStatus.UNKNOWN_FAILED);
        }
        UserQuestionResultVO userQuestionResultVO = new UserQuestionResultVO();
        if (status == CodeRunStatus.SUCCEED) {
            if (sandBoxExecuteResult.getOutputList() == null || sandBoxExecuteResult.getUseMemory() == null
                    || sandBoxExecuteResult.getUseTime() == null || judgeSubmitDTO.getDifficulty() == null
                    || judgeSubmitDTO.getSpaceLimit() == null || judgeSubmitDTO.getTimeLimit() == null) {
                throw new JudgeSystemException(CodeRunStatus.UNKNOWN_FAILED);
            }
            //比对直接结果 时间限制 空间限制比对
            userQuestionResultVO = doJudge(judgeSubmitDTO, sandBoxExecuteResult, userQuestionResultVO);
        } else {
            userQuestionResultVO.setPass(Constants.FALSE);
            userQuestionResultVO.setExeMessage(sandBoxExecuteResult.getExeMessage() == null
                    ? status.getMsg() : sandBoxExecuteResult.getExeMessage());

            userQuestionResultVO.setScore(JudgeConstants.ERROR_SCORE);
        }

        userQuestionResultVO.setSubmitId(judgeSubmitDTO.getSubmitId());
        userQuestionResultVO.setResultState(JudgeResultState.JUDGED);
        return returnSaved(judgeSubmitDTO, userSubmitStore.saveOrReuse(judgeSubmitDTO, userQuestionResultVO));
    }

    private UserQuestionResultVO doJudge(JudgeSubmitDTO judgeSubmitDTO, SandBoxExecuteResult sandBoxExecuteResult, UserQuestionResultVO userQuestionResultVO) {
        List<String> exeOutputList = sandBoxExecuteResult.getOutputList();
        List<String> outputList = judgeSubmitDTO.getOutputList();
        if (exeOutputList.size() != outputList.size()) {
            userQuestionResultVO.setPass(Constants.FALSE);
            userQuestionResultVO.setScore(JudgeConstants.ERROR_SCORE);
            userQuestionResultVO.setExeMessage(CodeRunStatus.NOT_ALL_PASSED.getMsg());

            return userQuestionResultVO;
        }

        List<UserExeResult> userExeResultList = new ArrayList<>();
        boolean isPass = resultCompare(judgeSubmitDTO, exeOutputList, outputList, userExeResultList);

        return assembleUserQuestionList(judgeSubmitDTO, sandBoxExecuteResult,
                userQuestionResultVO, userExeResultList, isPass);
    }

    private UserQuestionResultVO assembleUserQuestionList(JudgeSubmitDTO judgeSubmitDTO,
                                                                 SandBoxExecuteResult sandBoxExecuteResult,
                                                                 UserQuestionResultVO userQuestionResultVO,
                                                                 List<UserExeResult> userExeResultList,
                                                                 boolean isPass) {

        userQuestionResultVO.setUserExeResultList(userExeResultList);
        if (!isPass) {
            setUserQuestionResultVOProperties(userQuestionResultVO);
            userQuestionResultVO.setExeMessage(CodeRunStatus.NOT_ALL_PASSED.getMsg());

            return userQuestionResultVO;
        }

        if (sandBoxExecuteResult.getUseMemory() > judgeSubmitDTO.getSpaceLimit()) {
            setUserQuestionResultVOProperties(userQuestionResultVO);
            userQuestionResultVO.setExeMessage(CodeRunStatus.OUT_OF_MEMORY.getMsg());

            return userQuestionResultVO;
        }

        if (sandBoxExecuteResult.getUseTime() > judgeSubmitDTO.getTimeLimit()) {
            setUserQuestionResultVOProperties(userQuestionResultVO);
            userQuestionResultVO.setExeMessage(CodeRunStatus.OUT_OF_TIME.getMsg());

            return userQuestionResultVO;
        }

        userQuestionResultVO.setPass(Constants.TRUE);
        userQuestionResultVO.setExeMessage(CodeRunStatus.SUCCEED.getMsg());
        Integer score = judgeSubmitDTO.getDifficulty() * JudgeConstants.DEFAULT_SCORE;
        userQuestionResultVO.setScore(score);

        return userQuestionResultVO;
    }

    private void setUserQuestionResultVOProperties(UserQuestionResultVO userQuestionResultVO) {
        userQuestionResultVO.setPass(Constants.FALSE);
        userQuestionResultVO.setScore(JudgeConstants.ERROR_SCORE);
    }

    private boolean resultCompare(JudgeSubmitDTO judgeSubmitDTO, List<String> exeOutputList,
                                         List<String> outputList, List<UserExeResult> userExeResultList) {
        boolean isPass = true;
        for (int index = 0; index < outputList.size(); index++) {
            String output = outputList.get(index);
            String exeOutPut = exeOutputList.get(index);
            String input = judgeSubmitDTO.getInputList().get(index);
            UserExeResult userExeResult = new UserExeResult();
            userExeResult.setInput(input);
            userExeResult.setOutput(output);
            userExeResult.setExeOutput(exeOutPut);
            userExeResultList.add(userExeResult);
            if (!output.equals(exeOutPut)) {
                isPass = false;
                log.info("输入：{}， 期望输出：{}， 实际输出：{} ", input, output, exeOutputList);
            }
        }

        return isPass;
    }

    private UserQuestionResultVO returnSaved(JudgeSubmitDTO submission, UserSubmit saved) {
        UserQuestionResultVO result = UserSubmitStore.toResult(saved);
        try {
            stateCacheService.update(CacheConstants.getJudgeResultKey(submission.getUserId(), submission.getSubmitId()),
                    JudgeResultCache.judged(submission.getUserId(), result), CacheConstants.JUDGE_RESULT_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("真实结果已保存但缓存更新失败，submitId={}，原因={}", submission.getSubmitId(), e.getMessage());
        }
        return result;
    }

}
