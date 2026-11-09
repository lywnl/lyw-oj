package com.lyw.friend.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lyw.friend.domain.user.UserSubmit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserSubmitMapper extends BaseMapper<UserSubmit> {

    UserSubmit selectUserSubmit(@Param("userId") Long userId, @Param("submitId") Long submitId);

}
