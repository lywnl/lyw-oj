package com.lyw.friend.service.user;

import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.common.core.domain.Result;
import com.lyw.friend.domain.user.dto.UserQuestionSubmitDTO;

public interface IUserQuestionService {
    Result<UserQuestionResultVO> submit(UserQuestionSubmitDTO userQuestionSubmitDTO);

    boolean rabbitSubmit(UserQuestionSubmitDTO userQuestionSubmitDTO);

    UserQuestionResultVO getExeResult(Long examId, Long questionId, String currentTime);

}
