package com.lyw.system.service.question;


import com.github.pagehelper.PageHelper;
import com.lyw.system.domain.question.dto.QuestionQueryDTO;
import com.lyw.system.domain.question.vo.QuestionVO;
import com.lyw.system.mapper.question.QuestionMapper;
import lombok.extern.slf4j.Slf4j;
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
        System.out.println(queryDTO.toString());
        PageHelper.startPage(queryDTO.getPageNum(), queryDTO.getPageSize());
        List<QuestionVO> questionVOList = questionMapper.selectQuestionList(queryDTO);

        return questionVOList;
    }
}
