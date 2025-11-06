package com.lyw.system.controller.exam;

import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.system.domain.exam.dto.ExamAddDTO;
import com.lyw.system.domain.exam.dto.ExamEditDTO;
import com.lyw.system.domain.exam.dto.ExamQueryDTO;
import com.lyw.system.domain.exam.dto.ExamQuestionAddDTO;
import com.lyw.system.domain.exam.vo.ExamDetailVO;
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
    public Result<String> add(@RequestBody ExamAddDTO examAddDTO) {
        return Result.ok(examService.add(examAddDTO));
    }

    @PostMapping("/question/add")
    public Result<Void> addQuestion(@RequestBody ExamQuestionAddDTO examQuestionDTO) {
        return toR(examService.addQuestion(examQuestionDTO));
    }

    @DeleteMapping("/question/delete")
    public Result<Void> deleteQuestion( Long examId, Long questionId) {
        return toR(examService.deleteQuestion(examId, questionId));
    }

    @GetMapping("/detail")
    public Result<ExamDetailVO> detail(Long examId) {
        return Result.ok(examService.detail(examId));
    }

    /**
     * 更新
     * @param examEditDTO
     * @return
     */
    @PutMapping("/edit")
    public Result<Void> edit(@RequestBody ExamEditDTO examEditDTO) {
        return toR(examService.edit(examEditDTO));
    }

    /**
     * 删除竞赛
     * @param examId
     * @return
     */
    @DeleteMapping("/delete")
    public Result<Void> delete(Long examId) {
        return toR(examService.delete(examId));
    }

    @PutMapping("/publish")
    public Result<Void> publish(Long examId) {
        return toR(examService.publish(examId));
    }

    @PutMapping("/cancelPublish")
    public Result<Void> cancelPublish(Long examId) {
        return toR(examService.cancelPublish(examId));
    }

}
