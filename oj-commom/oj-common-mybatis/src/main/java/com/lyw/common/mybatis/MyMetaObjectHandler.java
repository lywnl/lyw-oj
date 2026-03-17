package com.lyw.common.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.lyw.common.core.constants.Constants;
import com.lyw.common.core.utils.ThreadLocalUtil;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class MyMetaObjectHandler implements MetaObjectHandler {
    @Override
    public void insertFill(MetaObject metaObject) {
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());

        // 创建人字段处理：如果获取不到当前用户ID，则使用默认值
        Long currentUserId = ThreadLocalUtil.get(Constants.USER_ID, Long.class);
        if (currentUserId == null) {
            currentUserId = 1L; // 或者使用其他默认值
        }
        this.strictInsertFill(metaObject, "createBy", Long.class, currentUserId);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
       this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
       this.strictUpdateFill(metaObject, "updateBy", Long.class, ThreadLocalUtil.get(Constants.USER_ID, Long.class));
    }

}
