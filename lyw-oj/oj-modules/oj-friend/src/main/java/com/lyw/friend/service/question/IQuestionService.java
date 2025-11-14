package com.lyw.friend.service.question;

import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.friend.domain.question.dto.QuestionQueryDTO;

public interface IQuestionService {

    TableDataInfo list(QuestionQueryDTO questionQueryDTO);

}
