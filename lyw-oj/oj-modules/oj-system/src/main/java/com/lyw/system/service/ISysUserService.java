package com.lyw.system.service;

import com.lyw.common.core.domain.Result;
import com.lyw.system.controller.LoginResult;

public interface ISysUserService {

    Result<Void> login(String userAccount, String password);

}
