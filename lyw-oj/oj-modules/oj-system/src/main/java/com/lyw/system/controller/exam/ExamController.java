package com.lyw.system.controller.exam;

import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.system.domain.exam.dto.ExamAddDTO;
import com.lyw.system.domain.exam.dto.ExamQueryDTO;
import com.lyw.system.service.exam.IExamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/exam")
public class ExamController extends BaseController {

    @Autowired
    private IExamService examService;

    @GetMapping("/list")
    public TableDataInfo list(ExamQueryDTO examQueryDTO) {
        return getTableDataInfo(examService.list(examQueryDTO));
    }

    @PostMapping("/add")
    public Result<Void> add(@RequestBody ExamAddDTO examAddDTO) {
        return toR(examService.add(examAddDTO));
    }
}
