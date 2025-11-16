package com.lyw.friend.controller.user;

import com.lyw.common.core.domain.Result;
import com.lyw.friend.domain.user.dto.UserQuestionSubmitDTO;
import com.lyw.friend.domain.user.vo.UserQuestionResultVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user/question")
public class UserQuestionController {

    @PostMapping("/submit")
    public Result<UserQuestionResultVO> submit(@RequestBody UserQuestionSubmitDTO userQuestionSubmitDTO) {
        return null;
    }
}
