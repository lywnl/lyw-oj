package com.lyw.friend.service.user.impl;

import cn.hutool.json.JSONUtil;
import com.lyw.api.domain.dto.JudgeSubmitDTO;
import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.api.service.RemoteJudgeService;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.enums.ProgramType;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.utils.ThreadLocalUtil;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.friend.domain.question.Question;
import com.lyw.friend.domain.question.QuestionCase;
import com.lyw.friend.domain.question.es.QuestionES;
import com.lyw.friend.domain.user.dto.UserQuestionSubmitDTO;
import com.lyw.friend.elasticsearch.QuestionRepository;
import com.lyw.friend.mapper.question.QuestionMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserQuestionServiceImpl implements IUserQuestionService{

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private RemoteJudgeService remoteJudgeService;

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
