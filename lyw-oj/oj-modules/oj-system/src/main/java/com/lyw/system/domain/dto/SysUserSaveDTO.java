package com.lyw.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SysUserSaveDTO {
    @NotBlank(message = "用户账号不能为空，并且不能只包含空格")
    @Size(min = 3, max = 10, message = "账号长度不能少于3位，不能大于10位")
    private String userAccount;
    @NotBlank(message = "用户密码不能为空，并且不能只包含空格")
    @Size(min = 5, max = 10, message = "密码长度不能少于5位，不能大于10位")
    private String password;
}
