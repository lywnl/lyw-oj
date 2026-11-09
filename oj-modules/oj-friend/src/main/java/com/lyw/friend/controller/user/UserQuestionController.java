package com.lyw.friend.controller.user;

import com.lyw.api.domain.vo.UserQuestionResultVO;
import com.lyw.api.domain.vo.UserQuestionSubmitVO;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.Result;
import com.lyw.friend.domain.user.dto.UserQuestionSubmitDTO;
import com.lyw.friend.service.user.IUserQuestionService;
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
    public Result<UserQuestionSubmitVO> rabbitSubmit(@RequestBody UserQuestionSubmitDTO userQuestionSubmitDTO) {
        return userQuestionService.rabbitSubmit(userQuestionSubmitDTO);
    }

    @GetMapping("/exe/result")
    public Result<UserQuestionResultVO> getExeResult(@RequestParam(required = false) String submitId,
            @RequestParam(defaultValue = "false") boolean checkDatabase) {
        if (submitId == null || !submitId.matches("[1-9][0-9]{0,18}")) {
            return Result.fail(ResultCode.FAILED_PARAMS_VALIDATE);
        }
        long id;
        try {
            id = Long.parseLong(submitId);
        } catch (NumberFormatException e) {
            return Result.fail(ResultCode.FAILED_PARAMS_VALIDATE);
        }
        return Result.ok(userQuestionService.getExeResult(id, checkDatabase));
    }
}
