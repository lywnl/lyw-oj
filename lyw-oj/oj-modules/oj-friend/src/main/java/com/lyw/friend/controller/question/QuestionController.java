package com.lyw.friend.controller.question;

import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.friend.domain.question.dto.QuestionQueryDTO;
import com.lyw.friend.domain.question.vo.QuestionDetailVO;
import com.lyw.friend.service.question.IQuestionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/question")
public class QuestionController extends BaseController {

    @Autowired
    private IQuestionService questionService;


    @GetMapping("/semiLogin/list")
    public TableDataInfo list(QuestionQueryDTO questionQueryDTO) {
        return questionService.list(questionQueryDTO);
    }

    @GetMapping("/detail")
    public Result<QuestionDetailVO> detail(Long questionId) {
        return Result.ok(questionService.detail(questionId));
    }

    @GetMapping("/preQuestion")
    public Result<String> preQuestion(Long questionId) {
        return Result.ok(questionService.preQuestion(questionId));
    }

    @GetMapping("/nextQuestion")
    public Result<String> nextQuestion(Long questionId) {
        return Result.ok(questionService.nextQuestion(questionId));
    }

    @GetMapping("/getFirstQuestion")
    public Result<String> getFirstQuestion(Long examId) {
        //获取竞赛中题目的顺序表  先从redis中获取  没有就查询数据库再同步redis  key是e:q:l:examId value是题目id的顺序表
        return Result.ok(questionService.getFirstQuestion(examId));
    }
}
