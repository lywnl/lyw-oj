package com.lyw.system.service;

import com.lyw.common.core.domain.Result;
import com.lyw.common.core.domain.vo.LoginUserVO;
import com.lyw.system.domain.dto.SysUserSaveDTO;

public interface ISysUserService {

    Result<String> login(String userAccount, String password);

    int add(SysUserSaveDTO sysUserSaveDTO);

    Result<LoginUserVO> info(String token);

    boolean logout(String token);

}
