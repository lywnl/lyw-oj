package com.lyw.friend.controller.user;

import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.friend.aspect.CheckUserStatus;
import com.lyw.friend.domain.exam.dto.ExamDTO;
import com.lyw.friend.domain.exam.dto.ExamQueryDTO;
import com.lyw.friend.service.user.IUserExamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/exam")
public class UserExamController extends BaseController {

    @Autowired
    private IUserExamService userExamService;

    /**
     * 报名竞赛
     * @param examDTO
     * @return
     */
    @CheckUserStatus
    @PostMapping("/enter")
    public Result<Void> enter( @RequestBody ExamDTO examDTO) {
        return toR(userExamService.enter(examDTO.getExamId()));
    }

    /**
     * 我的竞赛列表
     * @param examQueryDTO
     * @return
     */
    @GetMapping("/list")
    public TableDataInfo list(ExamQueryDTO examQueryDTO) {
        return userExamService.list(examQueryDTO);
    }


}
