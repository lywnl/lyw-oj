package com.lyw.system.service.exam;

import com.lyw.system.domain.exam.dto.ExamAddDTO;
import com.lyw.system.domain.exam.dto.ExamEditDTO;
import com.lyw.system.domain.exam.dto.ExamQueryDTO;
import com.lyw.system.domain.exam.dto.ExamQuestionAddDTO;
import com.lyw.system.domain.exam.vo.ExamDetailVO;
import com.lyw.system.domain.exam.vo.ExamVO;

import java.util.List;

public interface IExamService {

    List<ExamVO> list(ExamQueryDTO examQueryDTO);

    String add(ExamAddDTO examAddDTO);

    boolean addQuestion(ExamQuestionAddDTO examQuestionDTO);

    ExamDetailVO detail(Long examId);

    int edit(ExamEditDTO examEditDTO);

    int deleteQuestion(Long examId, Long questionId);

    int delete(Long examId);

    int publish(Long examId);

    int cancelPublish(Long examId);

}
