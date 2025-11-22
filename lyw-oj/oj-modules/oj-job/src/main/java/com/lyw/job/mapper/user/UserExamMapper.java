package com.lyw.job.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lyw.job.domain.user.UserExam;
import com.lyw.job.domain.user.UserScore;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface UserExamMapper extends BaseMapper<UserExam> {

    void updateUserScoreAndRank(List<UserScore> userScoreList);

}
