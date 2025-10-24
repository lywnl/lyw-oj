package com.lyw.system.controller;

import com.lyw.common.core.domain.Result;
import com.lyw.system.domain.LoginDTO;
import com.lyw.system.domain.SysUserSaveDTO;
import com.lyw.system.domain.SysUserVO;
import com.lyw.system.service.ISysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/sysUser")
@Tag(name = "管理员接口")
public class SysUserController {

    @Autowired
    private ISysUserService sysUserService;

    @PostMapping ("/login")
    @Operation(summary = "管理员登录", description = "根据提供的账号密码进行管理员登录登录")
    @ApiResponse(responseCode = "1000", description = "操作成功")
    @ApiResponse(responseCode = "2000", description = "服务器繁忙请稍后重试")
    @ApiResponse(responseCode = "3102", description = "用户不存在")
    @ApiResponse(responseCode = "3103", description = "账号或密码错误")
    public Result<String> login(@RequestBody LoginDTO loginDTO) {
        return sysUserService.login(loginDTO.getUserAccount(), loginDTO.getPassword());
    }

    @PostMapping("/add")
    @Operation(summary = "新增管理员", description = "根据提供的信息新增管理员")
    @ApiResponse(responseCode = "1000", description = "操作成功")
    @ApiResponse(responseCode = "2000", description = "服务器繁忙请稍后重试")
    @ApiResponse(responseCode = "3101", description = "用户已存在")
    public Result<Void> add(@RequestBody SysUserSaveDTO sysUserSaveDTO) {
        return null;
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "删除管理员", description = "根据用户id删除管理员")
    @Parameters(value =  {
            @Parameter(name = "userId", in = ParameterIn.PATH ,description = "用户id")
    })
    @ApiResponse(responseCode = "1000", description = "操作成功")
    @ApiResponse(responseCode = "2000", description = "服务器繁忙请稍后重试")
    @ApiResponse(responseCode = "3102", description = "用户不存在")
    public Result<Void> update(@PathVariable Long userId) {
        return null;
    }

    @GetMapping("/detail")
    @Operation(summary = "查询管理员详情", description = "根据用户id查询管理员详情")
    @Parameters(value =  {
            @Parameter(name = "userId", in = ParameterIn.QUERY ,description = "用户id"),
            @Parameter(name = "sex", in = ParameterIn.QUERY ,description = "性别")
    })
    @ApiResponse(responseCode = "1000", description = "操作成功")
    @ApiResponse(responseCode = "2000", description = "服务器繁忙请稍后重试")
    @ApiResponse(responseCode = "3102", description = "用户不存在")
    public Result<SysUserVO> detail(@RequestParam(required = true) Long userId, @RequestParam(required = false) String sex) {
        return null;
    }
}
