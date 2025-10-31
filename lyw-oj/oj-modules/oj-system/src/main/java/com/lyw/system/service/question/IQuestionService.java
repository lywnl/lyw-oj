package com.lyw.system.service.question;

import com.lyw.system.domain.question.dto.QuestionAddDTO;
import com.lyw.system.domain.question.dto.QuestionDetailVO;
import com.lyw.system.domain.question.dto.QuestionEditDTO;
import com.lyw.system.domain.question.dto.QuestionQueryDTO;
import com.lyw.system.domain.question.vo.QuestionVO;

import java.util.List;

public interface IQuestionService {

    List<QuestionVO> list(QuestionQueryDTO queryDTO);

    int add(QuestionAddDTO questionAddDTO);

    QuestionDetailVO detail(Long questionId);

    int edit(QuestionEditDTO questionEditDTO);

    int delete(Long questionId);
}
