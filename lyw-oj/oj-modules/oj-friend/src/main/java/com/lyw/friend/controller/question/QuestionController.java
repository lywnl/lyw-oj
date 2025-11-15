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
}
