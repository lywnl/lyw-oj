package com.lyw.system.mapper.user;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lyw.system.domain.user.User;
import com.lyw.system.domain.user.dto.UserQueryDTO;
import com.lyw.system.domain.user.vo.UserVO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    List<UserVO> selectUserList(UserQueryDTO queryDTO);
}
