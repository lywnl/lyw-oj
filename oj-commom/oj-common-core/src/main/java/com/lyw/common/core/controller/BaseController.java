package com.lyw.common.core.controller;

import cn.hutool.core.collection.CollectionUtil;
import com.github.pagehelper.PageInfo;
import com.lyw.common.core.domain.Result;
import com.lyw.common.core.domain.TableDataInfo;

import java.util.List;

public class BaseController {
    public Result<Void> toR(int rows) {
        return rows > 0 ? Result.ok() : Result.fail();
    }


    public Result<Void> toR(boolean result) {
        return result ? Result.ok() : Result.fail();
    }

    public TableDataInfo getTableDataInfo(List<?> list) {
        if (CollectionUtil.isEmpty(list)) {
            return TableDataInfo.empty();
        }
//        new PageInfo<>(list).getTotal(); //获取符合查询条件的数据的总数
//        return TableDataInfo.success(list, list.size());
        return TableDataInfo.success(list, new PageInfo<>(list).getTotal()); //返回符合条件的总数
    }
}
