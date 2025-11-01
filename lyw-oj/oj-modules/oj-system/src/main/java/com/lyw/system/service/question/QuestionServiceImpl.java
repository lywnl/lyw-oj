package com.lyw.system.service.question;


import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.pagehelper.PageHelper;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.system.domain.question.Question;
import com.lyw.system.domain.question.dto.QuestionAddDTO;
import com.lyw.system.domain.question.vo.QuestionDetailVO;
import com.lyw.system.domain.question.dto.QuestionEditDTO;
import com.lyw.system.domain.question.dto.QuestionQueryDTO;
import com.lyw.system.domain.question.vo.QuestionVO;
import com.lyw.system.mapper.question.QuestionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RefreshScope
@Slf4j
public class QuestionServiceImpl implements IQuestionService {

    @Autowired
    private QuestionMapper questionMapper;

    @Override
    public List<QuestionVO> list(QuestionQueryDTO queryDTO) {
        PageHelper.startPage(queryDTO.getPageNum(), queryDTO.getPageSize());
        List<QuestionVO> questionVOList = questionMapper.selectQuestionList(queryDTO);

        return questionVOList;
    }

    @Override
    public int add(QuestionAddDTO questionAddDTO) {
        List<Question> questions = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(Question::getTitle, questionAddDTO.getTitle()));

        if (CollectionUtil.isNotEmpty(questions)) {
            throw new ServiceException(ResultCode.FAILED_ALREADY_EXISTS);
        }

        Question question = new Question();
        BeanUtils.copyProperties(questionAddDTO, question);

        return questionMapper.insert(question);
    }

    @Override
    public QuestionDetailVO detail(Long questionId) {
        Question question = questionMapper.selectById(questionId);

        if (question == null) {
            throw new ServiceException(ResultCode.FAILED_NOT_EXISTS);
        }

        QuestionDetailVO questionDetailVO = new QuestionDetailVO();
        BeanUtils.copyProperties(question, questionDetailVO);
        return questionDetailVO;
    }

    @Override
    public int edit(QuestionEditDTO questionEditDTO) {
        Question oldQuestion = questionMapper.selectById(questionEditDTO.getQuestionId());

        if (oldQuestion == null) {
            throw new ServiceException(ResultCode.FAILED_NOT_EXISTS);
        }

        BeanUtils.copyProperties(questionEditDTO, oldQuestion);
        return questionMapper.updateById(oldQuestion);
    }

    @Override
    public int delete(Long questionId) {
        Question question = questionMapper.selectById(questionId);

        if (question == null) {
            throw new ServiceException(ResultCode.FAILED_NOT_EXISTS);
        }

        return questionMapper.deleteById(questionId);
    }


}
