package com.lyw.system.manager;


import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.redis.service.RedisService;
import com.lyw.system.domain.user.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class UserCacheManager {

    @Autowired
    private RedisService redisService;

    public void updateStatus(Long userId, Integer status) {
        //刷新用户缓存
        String userKey = getUserKey(userId);
        User user = redisService.getCacheObject(userKey, User.class);
        if (user == null) {
            return;
        }
        user.setStatus(status);
        redisService.setCacheObject(userKey, user);
        //设置用户缓存有效期为10分钟
        redisService.expire(userKey, CacheConstants.USER_EXP, TimeUnit.MINUTES);
    }

    //u:d:用户id
    private String getUserKey(Long userId) {
        return CacheConstants.USER_DETAIL + userId;
    }
}