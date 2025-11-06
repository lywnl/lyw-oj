package com.lyw.system.service.user;

import com.github.pagehelper.PageHelper;
import com.lyw.common.core.enums.ResultCode;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.system.domain.user.User;
import com.lyw.system.domain.user.dto.UserDTO;
import com.lyw.system.domain.user.dto.UserQueryDTO;
import com.lyw.system.domain.user.vo.UserVO;
import com.lyw.system.mapper.user.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class UserServiceImpl implements IUserService {

    @Autowired
    private UserMapper userMapper;

    @Override
    public List<UserVO> list(UserQueryDTO queryDTO) {
        PageHelper.startPage(queryDTO.getPageNum(), queryDTO.getPageSize());
        return userMapper.selectUserList(queryDTO);
    }

    @Override
    public int updateStatus(UserDTO userDTO) {
        User user = userMapper.selectById(userDTO.getUserId());

        if (user == null) {
            throw new ServiceException(ResultCode.FAILED_USER_NOT_EXISTS);
        }

        user.setStatus(userDTO.getStatus());

        return userMapper.updateById(user);
    }

}
