package com.lyw.friend.service.question;

import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.friend.domain.question.dto.QuestionQueryDTO;
import com.lyw.friend.domain.question.vo.QuestionDetailVO;

public interface IQuestionService {

    TableDataInfo list(QuestionQueryDTO questionQueryDTO);

    QuestionDetailVO detail(Long questionId);

}
