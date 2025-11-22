package com.lyw.friend.aspect;

import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.utils.ThreadLocalUtil;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.friend.domain.user.vo.UserVO;
import com.lyw.friend.manager.UserCacheManager;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Aspect
@Component
public class UserStatusCheckAspect {

    @Autowired
    private UserCacheManager userCacheManager;

    @Before(value = "@annotation(com.lyw.friend.aspect.CheckUserStatus)")
    public void before() {
        Long userId = ThreadLocalUtil.get(Constants.USER_ID, Long.class);
        UserVO userVO = userCacheManager.getUserById(userId);
        if (userVO == null) {
            throw new ServiceException(ResultCode.FAILED_USER_NOT_EXISTS);
        }

        if (Objects.equals(userVO.getStatus(), Constants.FALSE)) {
            throw new ServiceException(ResultCode.FAILED_USER_BANNED);
        }

    }
}
