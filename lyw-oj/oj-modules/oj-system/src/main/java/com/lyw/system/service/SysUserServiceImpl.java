package com.lyw.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.enums.UserIdentity;
import com.lyw.common.security.service.TokenService;
import com.lyw.system.domain.SysUser;
import com.lyw.system.mapper.SysUserMapper;
import com.lyw.system.utils.BCryptUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;


@Service
@RefreshScope
public class SysUserServiceImpl implements ISysUserService{

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private TokenService tokenService;

    @Value("${jwt.secret}")
    private String secret;

    @Override
    public Result<String> login(String userAccount, String password) {
        //通过账号查询数据库中的对应的用户信息
        LambdaQueryWrapper<SysUser> queryWrapper = new LambdaQueryWrapper<>();
        SysUser sysUser = sysUserMapper.selectOne(queryWrapper.select(SysUser::getUserId, SysUser::getPassword)
                .eq(SysUser::getUserAccount, userAccount));

        if (sysUser == null) {
            return Result.fail(ResultCode.FAILED_USER_NOT_EXISTS);
        }

        if (BCryptUtils.matchesPassword(password, sysUser.getPassword())) {
            String token = tokenService.createToken(sysUser.getUserId(), secret, UserIdentity.ADMIN.getCode());
            return Result.ok(token);
        }

        return Result.fail(ResultCode.FAILED_LOGIN);
    }
}
