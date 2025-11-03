package com.lyw.system.service.exam;

import com.lyw.system.domain.exam.dto.ExamAddDTO;
import com.lyw.system.domain.exam.dto.ExamQueryDTO;
import com.lyw.system.domain.exam.dto.ExamQuestionDTO;
import com.lyw.system.domain.exam.vo.ExamDetailVO;
import com.lyw.system.domain.exam.vo.ExamVO;

import java.util.List;

public interface IExamService {

    List<ExamVO> list(ExamQueryDTO examQueryDTO);

    String add(ExamAddDTO examAddDTO);

    boolean addQuestion(ExamQuestionDTO examQuestionDTO);

    ExamDetailVO detail(Long examId);

}
