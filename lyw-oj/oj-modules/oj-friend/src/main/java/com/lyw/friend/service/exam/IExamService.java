package com.lyw.friend.service.exam;

import com.lyw.friend.domain.exam.dto.ExamQueryDTO;
import com.lyw.friend.domain.exam.vo.ExamVO;

import java.util.List;

public interface IExamService {
    List<ExamVO> list(ExamQueryDTO examQueryDTO);

    List<ExamVO> redisList(ExamQueryDTO examQueryDTO);

}
