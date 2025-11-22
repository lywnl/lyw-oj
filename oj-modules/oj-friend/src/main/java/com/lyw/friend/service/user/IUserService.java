package com.lyw.friend.service.user;

import com.lyw.common.core.domain.Result;
import com.lyw.common.core.domain.vo.LoginUserVO;
import com.lyw.friend.domain.user.dto.UserDTO;
import com.lyw.friend.domain.user.dto.UserUpdateDTO;
import com.lyw.friend.domain.user.vo.UserVO;

public interface IUserService {
    boolean sendCode(UserDTO userDTO);

    String codeLogin(String phone, String code);

    boolean logout(String token);

    Result<LoginUserVO> info();

    UserVO detail();

    int edit(UserUpdateDTO userUpdateDTO);

    int updateHeadImage(String headImage);

}
