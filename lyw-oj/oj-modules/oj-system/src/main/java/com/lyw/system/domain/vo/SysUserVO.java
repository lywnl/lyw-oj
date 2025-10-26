package com.lyw.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SysUserVO {
    @Schema(description = "用户账号")
    private String userAccount;
    @Schema(description = "昵称")
    private String nickName;
}
