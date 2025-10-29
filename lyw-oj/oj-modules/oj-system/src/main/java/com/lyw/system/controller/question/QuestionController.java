package com.lyw.system.controller.question;

import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.TableDataInfo;
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
    public TableDataInfo list(@RequestBody QuestionQueryDTO queryDTO) {
        return getTableDataInfo(questionService.list(queryDTO));
    }
}
