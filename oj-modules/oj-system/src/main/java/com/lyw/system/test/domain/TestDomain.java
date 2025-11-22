package com.lyw.system.test.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@TableName("tb_test")
@Getter
@Setter
public class TestDomain {
    @TableId(type = IdType.ASSIGN_ID)
    private Integer testId;
    private String title;
    private String content;
}
