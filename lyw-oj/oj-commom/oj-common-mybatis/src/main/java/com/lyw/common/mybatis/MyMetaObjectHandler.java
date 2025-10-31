package com.lyw.common.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class MyMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        //创建人  获取当前用户用户id  如何获取当前调用接口的用户的id呢？
        this.strictInsertFill(metaObject, "createBy", Long.class, 1L);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
       this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
       this.strictUpdateFill(metaObject, "updateBy", Long.class, 1L);
    }
}
