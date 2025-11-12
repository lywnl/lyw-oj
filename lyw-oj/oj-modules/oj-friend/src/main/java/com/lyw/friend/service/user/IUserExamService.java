package com.lyw.friend.service.user;

import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.friend.domain.exam.dto.ExamQueryDTO;

import java.util.List;

public interface IUserExamService {
    int enter(Long examId);

    TableDataInfo list(ExamQueryDTO examQueryDTO);

}
