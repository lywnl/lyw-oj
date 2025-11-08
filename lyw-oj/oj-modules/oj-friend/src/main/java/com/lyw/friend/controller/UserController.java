package com.lyw.friend.controller;

import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.Result;
import com.lyw.friend.domain.dto.UserDTO;
import com.lyw.friend.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
public class UserController extends BaseController {

    @Autowired
    private IUserService userService;

    @PostMapping("/sendCode")
    public Result<Void> sendCode(@RequestBody UserDTO userDTO) {
       return toR(userService.sendCode(userDTO));
    }

    @PostMapping("/code/login")
    public Result<String> codeLogin(@RequestBody UserDTO userDTO) {
        return Result.ok(userService.codeLogin(userDTO.getPhone(), userDTO.getCode()));
    }
}
