package com.lyw.friend.service.user;

import com.lyw.friend.domain.user.dto.UserDTO;

public interface IUserService {
    boolean sendCode(UserDTO userDTO);

    String codeLogin(String phone, String code);

    boolean logout(String token);

}
