package com.lyw.friend.service.user.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.alibaba.fastjson2.JSON;
import com.lyw.api.domain.UserExeResult;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.api.service.RemoteJudgeService;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.enums.ProgramType;
import com.lyw.common.core.enums.QuestionResult;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.utils.ThreadLocalUtil;
import com.lyw.common.redis.service.RedisService;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.friend.domain.question.Question;
import com.lyw.friend.domain.question.QuestionCase;
import com.lyw.friend.domain.question.es.QuestionES;
import com.lyw.friend.domain.user.UserSubmit;
import com.lyw.friend.domain.user.dto.UserQuestionSubmitDTO;
import com.lyw.friend.elasticsearch.QuestionRepository;
import com.lyw.friend.mapper.question.QuestionMapper;
import com.lyw.friend.mapper.user.UserSubmitMapper;
import com.lyw.friend.rabbit.JudgeProducer;
import com.lyw.friend.service.user.IUserQuestionService;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class UserQuestionServiceImpl implements IUserQuestionService {

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private UserSubmitMapper userSubmitMapper;

    @Autowired
    private RemoteJudgeService remoteJudgeService;

    @Autowired
    private JudgeProducer judgeProducer;

    @Autowired
    private RedisService redisService;


    @Override
    public Result<UserQuestionResultVO> submit(UserQuestionSubmitDTO userQuestionSubmitDTO) {
        Integer programType = userQuestionSubmitDTO.getProgramType();
        if (ProgramType.JAVA.getValue().equals(programType)) {
            //按照java语言进行判题
            JudgeSubmitDTO judgeSubmitDTO = assembleJudgeSubmitDTO(userQuestionSubmitDTO);

            return remoteJudgeService.doJudgeJavaCode(judgeSubmitDTO);
        }

        throw new ServiceException(ResultCode.FAILED_NOT_SUPPORT_PROGRAM);
    }

    @Override
    public boolean rabbitSubmit(UserQuestionSubmitDTO userQuestionSubmitDTO) {
        Integer programType = userQuestionSubmitDTO.getProgramType();
        if (ProgramType.JAVA.getValue().equals(programType)) {
            JudgeSubmitDTO judgeSubmitDTO = assembleJudgeSubmitDTO(userQuestionSubmitDTO);
            // 先写Redis缓存标记"判题中"，再发MQ消息
            String key = CacheConstants.getJudgeResultKey(
                    judgeSubmitDTO.getUserId(), judgeSubmitDTO.getQuestionId(), judgeSubmitDTO.getExamId());
            UserQuestionResultVO judging = new UserQuestionResultVO();
            judging.setPass(QuestionResult.IN_JUDGE.getValue());
            redisService.setCacheObject(key, judging, CacheConstants.JUDGE_RESULT_TTL, TimeUnit.MINUTES);
            judgeProducer.produceMsg(judgeSubmitDTO);
            return true;
        }

        throw new ServiceException(ResultCode.FAILED_NOT_SUPPORT_PROGRAM);
    }

    @Override
    public UserQuestionResultVO getExeResult(Long examId, Long questionId, String currentTime) {
        Long userId = ThreadLocalUtil.get(Constants.USER_ID, Long.class);
        // 优先从Redis获取判题结果
        String key = CacheConstants.getJudgeResultKey(userId, questionId, examId);
        UserQuestionResultVO cachedResult = redisService.getCacheObject(key, UserQuestionResultVO.class);
        if (cachedResult != null) {
            // 判题已完成（非"判题中"状态），删除缓存并返回结果
            if (!QuestionResult.IN_JUDGE.getValue().equals(cachedResult.getPass())) {
                redisService.deleteObject(key);
            }
            return cachedResult;
        }
        // 缓存未命中（已过期），降级查数据库
        UserSubmit userSubmit = userSubmitMapper.selectCurrentUserSubmit(userId, questionId, examId, currentTime);
        UserQuestionResultVO userQuestionResultVO = new UserQuestionResultVO();
        if (userSubmit == null) {
            userQuestionResultVO.setPass(QuestionResult.IN_JUDGE.getValue());
        } else {
            userQuestionResultVO.setPass(userSubmit.getPass());
            userQuestionResultVO.setExeMessage(userSubmit.getExeMessage());
            if (StrUtil.isNotEmpty(userSubmit.getCaseJudgeRes())) {
                userQuestionResultVO.setUserExeResultList(JSON.parseArray(userSubmit.getCaseJudgeRes(), UserExeResult.class));
            }
        }
        return userQuestionResultVO;
    }


    private JudgeSubmitDTO assembleJudgeSubmitDTO(UserQuestionSubmitDTO submitDTO) {
        Long questionId = submitDTO.getQuestionId();
        QuestionES questionES = questionRepository.findById(questionId).orElse(null);
        JudgeSubmitDTO judgeSubmitDTO = new JudgeSubmitDTO();
        if (questionES != null) {
            BeanUtils.copyProperties(questionES, judgeSubmitDTO);
        } else {
            Question question = questionMapper.selectById(questionId);
            BeanUtils.copyProperties(question, judgeSubmitDTO);
            questionES = new QuestionES();
            BeanUtils.copyProperties(question, questionES);
            questionRepository.save(questionES);
        }

        judgeSubmitDTO.setUserId(ThreadLocalUtil.get(Constants.USER_ID, Long.class));
        judgeSubmitDTO.setExamId(submitDTO.getExamId());
        judgeSubmitDTO.setProgramType(submitDTO.getProgramType());
        judgeSubmitDTO.setUserCode(codeConnect(submitDTO.getUserCode(), questionES.getMainFuc()));

        List<QuestionCase> questionCaseList = JSONUtil.toList(questionES.getQuestionCase(), QuestionCase.class);
        List<String> inputList = questionCaseList.stream().map(questionCase -> questionCase.getInput()).toList();
        judgeSubmitDTO.setInputList(inputList);
        List<String> outputList = questionCaseList.stream().map(questionCase -> questionCase.getOutput()).toList();
        judgeSubmitDTO.setOutputList(outputList);

        return judgeSubmitDTO;
    }

    private String codeConnect(String userCode, String mainFunc) {
            String targetCharacter = "}";
            int targetLastIndex = userCode.lastIndexOf(targetCharacter);
            if (targetLastIndex != -1) {
            return userCode.substring(0, targetLastIndex) + "\n" + mainFunc + "\n" + userCode.substring(targetLastIndex);
        }
        throw new ServiceException(ResultCode.FAILED);
    }
}
