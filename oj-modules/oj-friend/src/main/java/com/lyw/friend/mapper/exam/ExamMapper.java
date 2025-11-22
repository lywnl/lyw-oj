package com.lyw.friend.mapper.exam;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lyw.friend.domain.exam.Exam;
import com.lyw.friend.domain.exam.dto.ExamQueryDTO;
import com.lyw.friend.domain.exam.vo.ExamVO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ExamMapper extends BaseMapper<Exam> {

    List<ExamVO> selectExamList(ExamQueryDTO examQueryDTO);

}
