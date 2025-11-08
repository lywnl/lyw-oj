package com.lyw.friend.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lyw.common.core.constants.CacheConstants;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.enums.UserIdentity;
import com.lyw.common.core.enums.UserStatus;
import com.lyw.common.message.service.AliSmsService;
import com.lyw.common.redis.service.RedisService;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.common.security.service.TokenService;
import com.lyw.friend.domain.User;
import com.lyw.friend.domain.dto.UserDTO;
import com.lyw.friend.mapper.UserMapper;
import com.lyw.friend.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class UserServiceImpl implements IUserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private RedisService redisService;

    @Autowired
    private AliSmsService aliSmsService;

    @Autowired
    private TokenService tokenService;

    @Value("${sms.code-expiration:5}")
    private Long phoneCodeExpiration;

    @Value("${sms.send-limit:50}")
    private Integer sendLimit;

    @Value("${sms.is-send:false}")
    private boolean isSend;  //开关打开：true  开关关闭false

    @Value("${jwt.secret}")
    private String secret;

    @Override
    public boolean sendCode(UserDTO userDTO) {
        if (!checkPhone(userDTO.getPhone())) {
            throw new ServiceException(ResultCode.FAILED_USER_PHONE);
        }

        String phoneCodeKey = getPhoneCodeKey(userDTO.getPhone());
        Long expire = redisService.getExpire(phoneCodeKey, TimeUnit.MINUTES);

        if (expire != null && (phoneCodeExpiration * 60 - expire) < 60) {
            throw new ServiceException(ResultCode.FAILED_FREQUENT);
        }

        //限制请求次数 每天限制50次 每日零点刷新
        String codeTimeKey = getCodeTimeKey(userDTO.getPhone());
        Long sendTimes = redisService.getCacheObject(codeTimeKey, Long.class);

        if (sendTimes != null && sendTimes > sendLimit) {
            throw new ServiceException(ResultCode.FAILED_TIME_LIMIT);
        }

        String code = isSend ? RandomUtil.randomNumbers(6) : Constants.DEFAULT_CODE;
        // 验证吗存储到redis中 类型string   key: p:c:手机号(唯一标识)  value: code
        redisService.setCacheObject(phoneCodeKey, code, phoneCodeExpiration, TimeUnit.MINUTES);

        if (isSend) {
            boolean sendMobileCode = aliSmsService.sendMobileCode(userDTO.getPhone(), code);
            if (!sendMobileCode) {
                throw new ServiceException(ResultCode.FAILED_SEND_CODE);
            }
        }

        redisService.increment(codeTimeKey);

        if (sendTimes == null) { //证明是当天第一次发起获取验证码的请求 开始计时发送次数的刷新时间
            long seconds = ChronoUnit.SECONDS.between(LocalDateTime.now(),
                    LocalDateTime.now().plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0));
            redisService.expire(codeTimeKey, seconds, TimeUnit.SECONDS);
        }

        return true;
    }

    @Override
    public String codeLogin(String phone, String code) {
        checkCode(phone, code);
        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));

        if (user == null) { //新用户
            //执行注册逻辑 插入用户数据
            user = new User();
            user.setPhone(phone);
            user.setStatus(UserStatus.NORMAL.getValue());
            userMapper.insert(user);
        }

        return tokenService.createToken(user.getUserId(), secret, UserIdentity.ORDINARY.getCode(), user.getNickName());

    }


    public static boolean checkPhone(String phone) {
        Pattern regex = Pattern.compile("^1[2|3|4|5|6|7|8|9][0-9]\\d{8}$");
        Matcher m = regex.matcher(phone);
        return m.matches();
    }

    private String getPhoneCodeKey(String phone) {
        return CacheConstants.PHONE_CODE_KEY + phone;
    }

    private String getCodeTimeKey(String phone) {
        return CacheConstants.CODE_TIMES_KEY + phone;
    }

    private void checkCode(String phone, String code) {
        String phoneCodeKey = getPhoneCodeKey(phone);
        String cacheCode = redisService.getCacheObject(phoneCodeKey, String.class);
        if (StrUtil.isEmpty(cacheCode)) {
            throw new ServiceException(ResultCode.FAILED_INVALID_CODE);
        }
        if (!cacheCode.equals(code)) {
            throw new ServiceException(ResultCode.FAILED_ERROR_CODE);
        }
        //验证码比对成功
        redisService.deleteObject(phoneCodeKey);
    }
}
