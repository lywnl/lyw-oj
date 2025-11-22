package com.lyw.friend.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lyw.friend.domain.user.UserSubmit;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserSubmitMapper extends BaseMapper<UserSubmit> {

    UserSubmit selectCurrentUserSubmit(Long userId, Long questionId, Long examId, String currentTime);

}
