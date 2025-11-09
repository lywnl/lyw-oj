package com.lyw.friend.service.exam.impl;

import com.github.pagehelper.PageHelper;
import com.lyw.friend.domain.exam.dto.ExamQueryDTO;
import com.lyw.friend.domain.exam.vo.ExamVO;
import com.lyw.friend.mapper.exam.ExamMapper;
import com.lyw.friend.service.exam.IExamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ExamService implements IExamService {

    @Autowired
    private ExamMapper examMapper;

    @Override
    public List<ExamVO> list(ExamQueryDTO examQueryDTO) {
        PageHelper.startPage(examQueryDTO.getPageNum(), examQueryDTO.getPageSize());
        return examMapper.selectExamList(examQueryDTO);
    }

    @Override
    public List<ExamVO> redisList(ExamQueryDTO examQueryDTO) {
        return null;
    }


}
