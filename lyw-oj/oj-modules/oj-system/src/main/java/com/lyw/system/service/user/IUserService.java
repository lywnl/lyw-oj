package com.lyw.system.service.user;

import com.lyw.system.domain.user.dto.UserDTO;
import com.lyw.system.domain.user.dto.UserQueryDTO;
import com.lyw.system.domain.user.vo.UserVO;

import java.util.List;

public interface IUserService {

    List<UserVO> list(UserQueryDTO queryDTO);

    int updateStatus(UserDTO userDTO);

}
