package com.lyw.friend.controller.exam;

import com.lyw.common.core.controller.BaseController;
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
        return getTableDataInfo(examService.redisList(examQueryDTO));
    }


}
