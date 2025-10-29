package com.lyw.system.service.question;

import com.lyw.system.domain.question.dto.QuestionQueryDTO;
import com.lyw.system.domain.question.vo.QuestionVO;

import java.util.List;

public interface IQuestionService {

    List<QuestionVO> list(QuestionQueryDTO queryDTO);

}
