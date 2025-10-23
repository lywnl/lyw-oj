package com.lyw.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.system.controller.LoginResult;
import com.lyw.system.domain.SysUser;
import com.lyw.system.mapper.SysUserMapper;
import com.lyw.system.utils.BCryptUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SysUserServiceImpl implements ISysUserService{

    @Autowired
    private SysUserMapper sysUserMapper;

    @Override
    public Result<Void> login(String userAccount, String password) {
        //通过账号查询数据库中的对应的用户信息
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        SysUser sysUser = sysUserMapper.selectOne(queryWrapper.select(SysUser::getPassword)
                .eq(SysUser::getUserAccount, userAccount));

        Result loginResult = new Result();

        if (sysUser == null) {
            return Result.fail(ResultCode.FAILED_USER_NOT_EXISTS);
        }

        if (BCryptUtils.matchesPassword(password, sysUser.getPassword())) {
            return Result.ok();
        }

        return Result.fail(ResultCode.FAILED_LOGIN);
    }
}
