package com.lyw.friend.service.user;

import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.api.domain.vo.UserQuestionSubmitVO;
import com.lyw.common.core.domain.Result;
import com.lyw.friend.domain.user.dto.UserQuestionSubmitDTO;

public interface IUserQuestionService {
    Result<UserQuestionResultVO> submit(UserQuestionSubmitDTO userQuestionSubmitDTO);

    Result<UserQuestionSubmitVO> rabbitSubmit(UserQuestionSubmitDTO userQuestionSubmitDTO);

    UserQuestionResultVO getExeResult(Long submitId, boolean checkDatabase);

}
