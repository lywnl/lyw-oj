package com.lyw.friend.service;

import com.lyw.friend.domain.dto.UserDTO;

public interface IUserService {
    boolean sendCode(UserDTO userDTO);

    String codeLogin(String phone, String code);
}
