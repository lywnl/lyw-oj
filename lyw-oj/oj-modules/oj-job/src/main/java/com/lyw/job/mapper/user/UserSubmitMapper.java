package com.lyw.job.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lyw.job.domain.user.UserScore;
import com.lyw.job.domain.user.UserSubmit;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface UserSubmitMapper extends BaseMapper<UserSubmit> {

    List<UserScore> selectUserScoreList(List<Long> examIdList);

}
