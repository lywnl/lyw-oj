package com.lyw.friend.controller.user;

import com.lyw.common.core.constants.HttpConstants;
import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.domain.vo.LoginUserVO;
import com.lyw.friend.domain.user.dto.UserDTO;
import com.lyw.friend.domain.user.vo.UserVO;
import com.lyw.friend.service.user.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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

    @DeleteMapping("/logout")
    public Result<Void> logout(@RequestHeader(HttpConstants.AUTHENTICATION) String token) {
        return toR(userService.logout(token));
    }

    @GetMapping("/info")
    public Result<LoginUserVO> info() {
        return userService.info();
    }

    @GetMapping("/detail")
    public Result<UserVO> detail() {
        return Result.ok(userService.detail());
    }

    @PutMapping("/edit")
    public Result<Void> edit(@RequestBody UserUpdateDTO userUpdateDTO) {

    }
}
