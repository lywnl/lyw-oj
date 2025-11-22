package com.lyw.friend.manager;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.redis.service.RedisService;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.friend.domain.question.Question;
import com.lyw.friend.mapper.question.QuestionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class QuestionCacheManager {

    @Autowired
    private RedisService redisService;

    @Autowired
    private QuestionMapper questionMapper;


    public Long getListSize() {
        return redisService.getListSize(CacheConstants.QUESTION_LIST);
    }

    public void refreshCache() {
        List<Question> questionList = questionMapper.selectList(new LambdaQueryWrapper<Question>()
                .select(Question::getQuestionId).orderByDesc(Question::getCreateTime));
        if (CollectionUtil.isEmpty(questionList)) {
            return;
        }

        List<Long> questionIdList = questionList.stream().map(question -> question.getQuestionId()).toList();

        redisService.rightPushAll(CacheConstants.QUESTION_LIST, questionIdList);
    }

    public Long preQuestion(Long questionId) {
        Long index = redisService.indexOfForList(CacheConstants.QUESTION_LIST, questionId);
        if (index == 0) {
            throw new ServiceException(ResultCode.FAILED_FIRST_QUESTION);
        }

        return redisService.indexForList(CacheConstants.QUESTION_LIST, index - 1, Long.class);
    }

    public Long nextQuestion(Long questionId) {
        Long index = redisService.indexOfForList(CacheConstants.QUESTION_LIST, questionId);
        Long listSize = getListSize();
        if (index == listSize - 1) {
            throw new ServiceException(ResultCode.FAILED_LAST_QUESTION);
        }

        return redisService.indexForList(CacheConstants.QUESTION_LIST, index + 1, Long.class);
    }

}
