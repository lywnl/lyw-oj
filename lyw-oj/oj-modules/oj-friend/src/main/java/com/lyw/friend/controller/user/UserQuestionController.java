package com.lyw.friend.controller.user;

import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.Result;
import com.lyw.friend.domain.user.dto.UserQuestionSubmitDTO;
import com.lyw.friend.service.user.impl.IUserQuestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/question")
public class UserQuestionController extends BaseController {


    @Autowired
    private IUserQuestionService userQuestionService;

    @PostMapping("/submit")
    public Result<UserQuestionResultVO> submit(@RequestBody UserQuestionSubmitDTO userQuestionSubmitDTO) {
        return userQuestionService.submit(userQuestionSubmitDTO);
    }

    @PostMapping("/rabbit/submit")
    public Result<Void> rabbitSubmit(@RequestBody UserQuestionSubmitDTO userQuestionSubmitDTO) {
        return toR(userQuestionService.rabbitSubmit(userQuestionSubmitDTO));
    }

    @GetMapping("/exe/result")
    public Result<UserQuestionResultVO> getExeResult(Long examId, Long questionId, String currentTime) {
        return Result.ok(userQuestionService.getExeResult(examId, questionId, currentTime));
    }
}
