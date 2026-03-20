package com.lyw.judge.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lyw.api.domain.UserExeResult;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.constants.JudgeConstants;
import com.lyw.common.core.enums.CodeRunStatus;
import com.lyw.common.redis.service.RedisService;
import com.lyw.judge.domain.SandBoxExecuteResult;
import com.lyw.judge.domain.UserSubmit;
import com.lyw.judge.mapper.UserSubmitMapper;
import com.lyw.judge.service.IJudgeService;
import com.lyw.judge.service.ISandboxPoolService;
import com.lyw.judge.service.ISandboxService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class JudgeServiceImpl implements IJudgeService {

    @Autowired
    private ISandboxService sandboxService;

    @Autowired
    private ISandboxPoolService sandboxPoolService;

    @Autowired
    private UserSubmitMapper userSubmitMapper;

    @Autowired
    private RedisService redisService;

    @Override
    public UserQuestionResultVO doJudgeJavaCode(JudgeSubmitDTO judgeSubmitDTO) {
        SandBoxExecuteResult sandBoxExecuteResult = sandboxPoolService
                .exeJavaCode(judgeSubmitDTO.getUserId(), judgeSubmitDTO.getUserCode(), judgeSubmitDTO.getInputList());
        UserQuestionResultVO userQuestionResultVO = new UserQuestionResultVO();
        if (sandBoxExecuteResult != null && CodeRunStatus.SUCCEED.equals(sandBoxExecuteResult.getRunStatus())) {
            //比对直接结果 时间限制 空间限制比对
            userQuestionResultVO = doJudge(judgeSubmitDTO, sandBoxExecuteResult, userQuestionResultVO);
        } else {
            userQuestionResultVO.setPass(Constants.FALSE);
            if (sandBoxExecuteResult != null) {
                userQuestionResultVO.setExeMessage(sandBoxExecuteResult.getExeMessage());
            } else {
                userQuestionResultVO.setExeMessage(CodeRunStatus.UNKNOWN_FAILED.getMsg());
            }

            userQuestionResultVO.setScore(JudgeConstants.ERROR_SCORE);
        }

        // 先更新Redis缓存（让前端轮询立即感知结果），再写数据库
        updateJudgeResultCache(judgeSubmitDTO, userQuestionResultVO);
        saveUserSubmit(judgeSubmitDTO, userQuestionResultVO);
        return userQuestionResultVO;
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

    private void updateJudgeResultCache(JudgeSubmitDTO judgeSubmitDTO, UserQuestionResultVO userQuestionResultVO) {
        String key = CacheConstants.getJudgeResultKey(
                judgeSubmitDTO.getUserId(), judgeSubmitDTO.getQuestionId(), judgeSubmitDTO.getExamId());
        redisService.setCacheObject(key, userQuestionResultVO, CacheConstants.JUDGE_RESULT_TTL, TimeUnit.MINUTES);
    }

    private void saveUserSubmit(JudgeSubmitDTO judgeSubmitDTO, UserQuestionResultVO userQuestionResultVO) {
        UserSubmit userSubmit = new UserSubmit();
        BeanUtils.copyProperties(userQuestionResultVO, userSubmit);
        userSubmit.setUserId(judgeSubmitDTO.getUserId());
        userSubmit.setQuestionId(judgeSubmitDTO.getQuestionId());
        userSubmit.setExamId(judgeSubmitDTO.getExamId());
        userSubmit.setProgramType(judgeSubmitDTO.getProgramType());
        userSubmit.setUserCode(judgeSubmitDTO.getUserCode());
        userSubmit.setCaseJudgeRes(JSON.toJSONString(userQuestionResultVO.getUserExeResultList()));
        userSubmit.setCreateBy(judgeSubmitDTO.getUserId());

        userSubmitMapper.insert(userSubmit);
    }

}
