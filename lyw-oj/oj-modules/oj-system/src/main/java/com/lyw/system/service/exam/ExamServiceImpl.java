package com.lyw.system.service.exam;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.pagehelper.PageHelper;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.system.domain.exam.Exam;
import com.lyw.system.domain.exam.dto.ExamAddDTO;
import com.lyw.system.domain.exam.dto.ExamQueryDTO;
import com.lyw.system.domain.exam.vo.ExamVO;
import com.lyw.system.mapper.exam.ExamMapper;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ExamServiceImpl implements IExamService {

    @Autowired
    private ExamMapper examMapper;

    @Override
    public List<ExamVO> list(ExamQueryDTO examQueryDTO) {
        PageHelper.startPage(examQueryDTO.getPageNum(), examQueryDTO.getPageSize());
        return examMapper.selectExamList(examQueryDTO);
    }

    @Override
    public int add(ExamAddDTO examAddDTO) {
        List<Exam> exams = examMapper.selectList(new LambdaQueryWrapper<Exam>().eq(Exam::getTitle, examAddDTO.getTitle()));

        if (CollectionUtil.isNotEmpty(exams)) {
            throw new ServiceException(ResultCode.FAILED_ALREADY_EXISTS);
        }

        if (examAddDTO.getStartTime().isBefore(LocalDateTime.now())) {
            throw new ServiceException(ResultCode.EXAM_START_TIME_BEFORE_CURRENT_TIME);
        }

        if (examAddDTO.getStartTime().isAfter(examAddDTO.getEndTime())) {
            throw new ServiceException(ResultCode.EXAM_START_TIME_AFTER_END_TIME);
        }

        Exam exam = new Exam();
        BeanUtils.copyProperties(examAddDTO, exam);
        return examMapper.insert(exam);
    }
}
