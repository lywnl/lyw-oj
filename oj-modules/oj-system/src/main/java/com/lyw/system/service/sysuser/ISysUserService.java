package com.lyw.system.service.sysuser;

import com.lyw.common.core.domain.Result;
import com.lyw.common.core.domain.vo.LoginUserVO;
import com.lyw.system.domain.sysuser.dto.SysUserSaveDTO;

public interface ISysUserService {

    Result<String> login(String userAccount, String password);

    int add(SysUserSaveDTO sysUserSaveDTO);

    Result<LoginUserVO> info(String token);

    boolean logout(String token);

}
