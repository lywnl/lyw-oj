package com.lyw.system.service.question.impl;


import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.pagehelper.PageHelper;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.system.domain.question.Question;
import com.lyw.system.domain.question.dto.QuestionAddDTO;
import com.lyw.system.domain.question.es.QuestionES;
import com.lyw.system.domain.question.vo.QuestionDetailVO;
import com.lyw.system.domain.question.dto.QuestionEditDTO;
import com.lyw.system.domain.question.dto.QuestionQueryDTO;
import com.lyw.system.domain.question.vo.QuestionVO;
import com.lyw.system.es.QuestionRepository;
import com.lyw.system.manager.QuestionCacheManager;
import com.lyw.system.mapper.question.QuestionMapper;
import com.lyw.system.service.question.IQuestionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RefreshScope
@Slf4j
public class QuestionServiceImpl implements IQuestionService {

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private QuestionCacheManager questionCacheManager;

    @Override
    public List<QuestionVO> list(QuestionQueryDTO queryDTO) {
        String excludeIdStr = queryDTO.getExcludeIdStr();

        if (StrUtil.isNotEmpty(excludeIdStr)) {
            String[] excludeIdArr = excludeIdStr.split(Constants.SPLIT_SEM);

            Set<Long> excludeIdSet = Arrays.stream(excludeIdArr).map(Long::valueOf)
                    .collect(Collectors.toSet());

            queryDTO.setExcludeIdSet(excludeIdSet);
        }

        PageHelper.startPage(queryDTO.getPageNum(), queryDTO.getPageSize());
        return questionMapper.selectQuestionList(queryDTO);
    }
    @Override
    public boolean add(QuestionAddDTO questionAddDTO) {
        List<Question> questions = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .eq(Question::getTitle, questionAddDTO.getTitle()));
        if (CollectionUtil.isNotEmpty(questions)) {
            throw new ServiceException(ResultCode.FAILED_ALREADY_EXISTS);
        }

        Question question = new Question();
        BeanUtils.copyProperties(questionAddDTO, question);

        int insert = questionMapper.insert(question);
        if (insert <= 0) {
            return false;
        }

        QuestionES questionES = new QuestionES();
        BeanUtils.copyProperties(question, questionES);
        questionRepository.save(questionES);
        questionCacheManager.addCache(question.getQuestionId());

        return true;
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
        QuestionES questionES = new QuestionES();
        BeanUtils.copyProperties(oldQuestion, questionES);
        questionRepository.save(questionES);

        return questionMapper.updateById(oldQuestion);
    }

    @Override
    public int delete(Long questionId) {
        Question question = questionMapper.selectById(questionId);

        if (question == null) {
            throw new ServiceException(ResultCode.FAILED_NOT_EXISTS);
        }

        questionRepository.deleteById(questionId);
        questionCacheManager.deleteCache(questionId);

        return questionMapper.deleteById(questionId);
    }


}
