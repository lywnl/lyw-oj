package com.lyw.friend.controller.exam;

import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.friend.domain.exam.dto.ExamQueryDTO;
import com.lyw.friend.service.exam.IExamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/exam")
public class ExamController extends BaseController {

    @Autowired
    private IExamService examService;

    @GetMapping("/semiLogin/list")
    public TableDataInfo list(ExamQueryDTO examQueryDTO) {
        return getTableDataInfo(examService.list(examQueryDTO));
    }

    @GetMapping("/semiLogin/redis/list")
    public TableDataInfo redisList(ExamQueryDTO examQueryDTO) {
        return examService.redisList(examQueryDTO);
    }

    @GetMapping("/getFirstQuestion")
    public Result<String> getFirstQuestion(Long examId) {
        //获取竞赛中题目的顺序表  先从redis中获取  没有就查询数据库再同步redis  key是e:q:l:examId value是题目id的顺序表
        return Result.ok(examService.getFirstQuestion(examId));
    }

    @GetMapping("/preQuestion")
    public Result<String> preQuestion(Long examId, Long questionId) {
        return Result.ok(examService.preQuestion(examId, questionId));
    }

    @GetMapping("/nextQuestion")
    public Result<String> nextQuestion(Long examId, Long questionId) {
        return Result.ok(examService.nextQuestion(examId, questionId));
    }

}
