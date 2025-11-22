package com.lyw.system.controller.question;

import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.system.domain.question.dto.QuestionAddDTO;
import com.lyw.system.domain.question.vo.QuestionDetailVO;
import com.lyw.system.domain.question.dto.QuestionEditDTO;
import com.lyw.system.domain.question.dto.QuestionQueryDTO;
import com.lyw.system.service.question.IQuestionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/question")
@Tag(name = "题目管理接口")
public class QuestionController extends BaseController {

    @Autowired
    private IQuestionService questionService;

    @GetMapping("/list")
    public TableDataInfo list(QuestionQueryDTO queryDTO) {
        return getTableDataInfo(questionService.list(queryDTO));
    }

    @PostMapping("/add")
    public Result<Void> add(@RequestBody QuestionAddDTO questionAddDTO) {
        return toR(questionService.add(questionAddDTO));
    }

    @GetMapping("/detail")
    public Result<QuestionDetailVO> detail(Long questionId) {
        QuestionDetailVO detail = questionService.detail(questionId);
        return Result.ok(detail);
    }

    @PutMapping("/edit")
    public Result<Void> edit(@RequestBody QuestionEditDTO questionEditDTO) {
        return toR(questionService.edit(questionEditDTO));
    }

    @DeleteMapping("/delete")
    public Result<Void> delete(Long questionId) {
        return toR(questionService.delete(questionId));
    }
}
