package com.lyw.system.mapper.exam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lyw.system.domain.exam.Exam;
import com.lyw.system.domain.exam.dto.ExamQueryDTO;
import com.lyw.system.domain.exam.vo.ExamVO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ExamMapper extends BaseMapper<Exam> {

    List<ExamVO> selectExamList(ExamQueryDTO examQueryDTO);

}
