package com.lyw.friend.service.question.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.friend.domain.question.Question;
import com.lyw.friend.domain.question.dto.QuestionQueryDTO;
import com.lyw.friend.domain.question.es.QuestionES;
import com.lyw.friend.domain.question.vo.QuestionDetailVO;
import com.lyw.friend.domain.question.vo.QuestionVO;
import com.lyw.friend.elasticsearch.QuestionRepository;
import com.lyw.friend.manager.QuestionCacheManager;
import com.lyw.friend.mapper.question.QuestionMapper;
import com.lyw.friend.service.question.IQuestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class QuestionServiceImpl implements IQuestionService {

    @Autowired
    private QuestionRepository questionRepository;

    @Autowired
    private QuestionMapper questionMapper;

    @Autowired
    private QuestionCacheManager questionCacheManager;

    @Override
    public TableDataInfo list(QuestionQueryDTO questionQueryDTO) {
        long count = questionRepository.count();
        if (count <= 0) {
            refreshQuestion();
        }

        Sort sort = Sort.by(Sort.Direction.DESC, "createTime");
        PageRequest pageRequest = PageRequest.of(questionQueryDTO.getPageNum() - 1, questionQueryDTO.getPageSize(), sort);

        Integer difficulty = questionQueryDTO.getDifficulty();
        String keyword = questionQueryDTO.getKeyword();
        Page<QuestionES> questionESPage;
        if (difficulty == null && StrUtil.isEmpty(keyword)) {
            questionESPage = questionRepository.findAll(pageRequest);
        } else if (StrUtil.isEmpty(keyword)) {
            questionESPage = questionRepository.findQuestionByDifficulty(difficulty, pageRequest);
        } else if (difficulty == null) {
            questionESPage = questionRepository.findByTitleOrContent(keyword, keyword, pageRequest);
        } else {
            questionESPage = questionRepository.findByTitleOrContentAndDifficulty(keyword, keyword, difficulty, pageRequest);
        }

        long total = questionESPage.getTotalElements();
        if (total <= 0) {
            return TableDataInfo.empty();
        }

        List<QuestionES> questionESList = questionESPage.getContent();
        List<QuestionVO> questionVOList = BeanUtil.copyToList(questionESList, QuestionVO.class);

        return TableDataInfo.success(questionVOList, total);
    }

    @Override
    public QuestionDetailVO detail(Long questionId) {
        QuestionES questionES = questionRepository.findById(questionId).orElse(null);
        QuestionDetailVO questionDetailVO = new QuestionDetailVO();
        if (questionES != null) {
            BeanUtil.copyProperties(questionES, questionDetailVO);
            return questionDetailVO;
        }

        Question question = questionMapper.selectById(questionId);
        if (question == null) {
            return null;
        }

        refreshQuestion();
        BeanUtil.copyProperties(question, questionDetailVO);

        return questionDetailVO;
    }

    @Override
    public String preQuestion(Long questionId) {
        Long listSize = questionCacheManager.getListSize();
        if (listSize == null || listSize <= 0) {
            questionCacheManager.refreshCache();
        }

        return questionCacheManager.preQuestion(questionId).toString();
    }

    @Override
    public String nextQuestion(Long questionId) {
        Long listSize = questionCacheManager.getListSize();
        if (listSize == null || listSize <= 0) {
            questionCacheManager.refreshCache();
        }

        return questionCacheManager.nextQuestion(questionId).toString();
    }

    private void refreshQuestion() {
        List<Question> questionList = questionMapper.selectList(new LambdaQueryWrapper<>());
        if (questionList == null) {
            return;
        }

        List<QuestionES> questionESList = BeanUtil.copyToList(questionList, QuestionES.class);
        questionRepository.saveAll(questionESList);
    }
}
