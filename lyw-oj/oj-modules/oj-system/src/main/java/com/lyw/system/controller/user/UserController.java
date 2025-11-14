package com.lyw.system.controller.user;

import com.lyw.common.core.controller.BaseController;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.domain.TableDataInfo;
import com.lyw.system.domain.user.dto.UserDTO;
import com.lyw.system.domain.user.dto.UserQueryDTO;
import com.lyw.system.service.user.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController extends BaseController {

    @Autowired
    private IUserService userService;

    @GetMapping("/list")
    public TableDataInfo list(UserQueryDTO queryDTO) {
        return getTableDataInfo(userService.list(queryDTO));
    }


    @PutMapping("/updateStatus")
    public Result<Void> updateStatus(@RequestBody UserDTO userDTO) {
        //TODO 拉黑: 限制用户功能 解禁: 放开对拉黑用户的限制
        return toR(userService.updateStatus(userDTO));
    }


}
