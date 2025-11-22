package com.lyw.system.service.sysuser.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lyw.common.core.constants.HttpConstants;
import com.lyw.common.core.domain.LoginUser;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.domain.vo.LoginUserVO;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.core.enums.UserIdentity;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.common.security.service.TokenService;
import com.lyw.system.domain.sysuser.SysUser;
import com.lyw.system.domain.sysuser.dto.SysUserSaveDTO;
import com.lyw.system.mapper.sysuser.SysUserMapper;
import com.lyw.system.service.sysuser.ISysUserService;
import com.lyw.system.utils.BCryptUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
@RefreshScope
public class SysUserServiceImpl implements ISysUserService {

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
        SysUser sysUser = sysUserMapper.selectOne(queryWrapper.select(SysUser::getUserId, SysUser::getPassword, SysUser::getNickName)
                .eq(SysUser::getUserAccount, userAccount));

        if (sysUser == null) {
            return Result.fail(ResultCode.FAILED_USER_NOT_EXISTS);
        }

        if (BCryptUtils.matchesPassword(password, sysUser.getPassword())) {
            String token = tokenService.createToken(sysUser.getUserId(), secret, UserIdentity.ADMIN.getCode(), sysUser.getNickName(), null);
            return Result.ok(token);
        }

        return Result.fail(ResultCode.FAILED_LOGIN);
    }

    @Override
    public boolean logout(String token) {
        if (StrUtil.isNotEmpty(token) && token.startsWith(HttpConstants.PREFIX)) {
            token = token.replaceFirst(HttpConstants.PREFIX, StrUtil.EMPTY);
        }
        return tokenService.deleteLoginUser(token, secret);
    }
    @Override
    public int add(SysUserSaveDTO sysUserSaveDTO) {
        //是否重复
        List<SysUser> sysUserList = sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUserAccount, sysUserSaveDTO.getUserAccount()));

        if (CollectionUtil.isNotEmpty(sysUserList)) {
            //用户已存在
            //自定义异常
            throw new ServiceException(ResultCode.AILED_USER_EXISTS);
        }

        //将DTO转化为实体
        SysUser sysUser = new SysUser();
        sysUser.setUserAccount(sysUserSaveDTO.getUserAccount());
        sysUser.setPassword(BCryptUtils.encryptPassword(sysUserSaveDTO.getPassword()));

        return sysUserMapper.insert(sysUser);

    }

    @Override
    public Result<LoginUserVO> info(String token) {
        if (StrUtil.isNotEmpty(token) && token.startsWith(HttpConstants.PREFIX)) {
            token = token.replaceFirst(HttpConstants.PREFIX, StrUtil.EMPTY);
        }

        LoginUser loginUser = tokenService.getLoginUser(token, secret);
        if (loginUser == null) {
            return Result.fail();
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        loginUserVO.setNickName(loginUser.getNickname());
        return Result.ok(loginUserVO);
    }


}
