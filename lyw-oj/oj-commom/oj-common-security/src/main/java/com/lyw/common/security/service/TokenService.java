package com.lyw.common.security.service;

import cn.hutool.core.lang.UUID;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.constants.JwtConstants;
import com.lyw.common.core.domain.LoginUser;
import com.lyw.common.core.enums.UserIdentity;
import com.lyw.common.redis.service.RedisService;
import com.lyw.common.core.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class TokenService {

    @Autowired
    private RedisService redisService;

    public String createToken(Long userId, String secret, Integer identity, String nickname) {
        Map<String, Object> claims = new HashMap<>();
        String userKey = UUID.fastUUID().toString();
        claims.put(JwtConstants.LOGIN_USER_ID, userId);
        claims.put(JwtConstants.LOGIN_USER_KEY, userKey);
        String token = JwtUtils.createToken(claims, secret);

        //使用redis存放敏感用户信息 redis字段 identity 1表示普通用户 2表示管理员
        //便于维护 使用统一前缀 logintoken:userId(雪花算法生成的) 或者其他的UUID等
        String tokenKey = getTokenKey(userKey);
        LoginUser loginUser = new LoginUser();
        loginUser.setIdentity(identity);
        loginUser.setNickname(nickname);

        //存放用户信息到redis
        redisService.setCacheObject(tokenKey, loginUser, CacheConstants.EXP, TimeUnit.MINUTES);

        return token;
    }

    //验证身份之后才会调用的, 并且在请求到达controller之前才会调用此方法
    public void extendToken(String token, String secret) {
        String userKey = getUserKey(token, secret);
        String tokenKey = getTokenKey(userKey);

        //720min == 12h 剩余180min的时候对他延长
        Long expire = redisService.getExpire(tokenKey, TimeUnit.MINUTES);
        if (expire != null && expire < CacheConstants.REFRESH_TIME) {
            redisService.expire(tokenKey, CacheConstants.EXP, TimeUnit.MINUTES); //重新设置为720min
        }
    }

    private String getTokenKey(String userKey) {
        return CacheConstants.LOGIN_TOKEN_KEY + userKey;
    }

    public Claims getClaims(String token, String secret) {
        Claims claims;
        try {
            claims = JwtUtils.parseToken(token, secret); //获取令牌中信息  解析payload中信息  存储着用户唯一标识信息
            if (claims == null) {
                log.error("解析token：{}, 出现异常", token);
                return null;
            }
        } catch (Exception e) {
            log.error("解析token：{}, 出现异常", token, e);
            return null;
        }
        return claims;
    }

    public LoginUser getLoginUser(String token, String secret) {
        String userKey = getUserKey(token, secret);
        if (userKey == null) {
            return null;
        }

        return redisService.getCacheObject(getTokenKey(userKey), LoginUser.class);
    }

    public String getUserKey(String token, String secret) {
        Claims claims;
        try {
            claims = JwtUtils.parseToken(token, secret);
            if (claims == null) {
                log.error("解析token:{}, 出现异常", token);
                return null;
            }
        } catch (Exception e) {
            log.error("解析token:{}, 出现异常", token, e);
            return null;
        }

        return JwtUtils.getUserKey(claims);
    }

    public boolean deleteLoginUser(String token, String secret) {
        String userKey = getUserKey(token, secret);
        if (userKey == null) {
            return false;
        }

        return redisService.deleteObject(getTokenKey(userKey));
    }
}
