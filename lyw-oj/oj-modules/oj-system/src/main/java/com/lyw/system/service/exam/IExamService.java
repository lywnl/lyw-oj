package com.lyw.system.service.exam;

import com.lyw.system.domain.exam.dto.ExamAddDTO;
import com.lyw.system.domain.exam.dto.ExamQueryDTO;
import com.lyw.system.domain.exam.vo.ExamVO;

import java.util.List;

public interface IExamService {

    List<ExamVO> list(ExamQueryDTO examQueryDTO);

    int add(ExamAddDTO examAddDTO);

}
