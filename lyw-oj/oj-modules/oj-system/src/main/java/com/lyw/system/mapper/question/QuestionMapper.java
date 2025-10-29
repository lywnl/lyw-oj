package com.lyw.system.mapper.question;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lyw.system.domain.question.Question;
import com.lyw.system.domain.question.dto.QuestionQueryDTO;
import com.lyw.system.domain.question.vo.QuestionVO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface QuestionMapper extends BaseMapper<Question> {

    List<QuestionVO> selectQuestionList(QuestionQueryDTO queryDTO);

}
