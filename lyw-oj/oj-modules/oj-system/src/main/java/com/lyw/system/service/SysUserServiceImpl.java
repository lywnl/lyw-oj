package com.lyw.system.service;

import cn.hutool.core.lang.UUID;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.enums.UserIdentity;
import com.lyw.common.redis.service.RedisService;
import com.lyw.common.core.constants.JwtConstants;
import com.lyw.common.security.utils.JwtUtils;
import com.lyw.system.domain.LoginUser;
import com.lyw.system.domain.SysUser;
import com.lyw.system.mapper.SysUserMapper;
import com.lyw.system.utils.BCryptUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RefreshScope
public class SysUserServiceImpl implements ISysUserService{

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private RedisService redisService;

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
            Map<String, Object> claims = new HashMap<>();
            String uuid = UUID.fastUUID().toString();
            claims.put(JwtConstants.LOGIN_USER_ID, sysUser.getUserId());
            claims.put(JwtConstants.LOGIN_USER_KEY, uuid);
            String token = JwtUtils.createToken(claims, secret);

            //使用redis存放敏感用户信息 redis字段 identity 1表示普通用户 2表示管理员
            //便于维护 使用统一前缀 logintoken:userId(雪花算法生成的) 或者其他的UUID等
            String key = CacheConstants.LOGIN_TOKEN_KEY + uuid;
            LoginUser loginUser = new LoginUser();
            loginUser.setIdentity(UserIdentity.ADMIN.getCode());

            //存放用户信息到redis
            redisService.setCacheObject(key, loginUser, CacheConstants.EXP, TimeUnit.MINUTES);

            return Result.ok(token);
        }

        return Result.fail(ResultCode.FAILED_LOGIN);
    }
}
