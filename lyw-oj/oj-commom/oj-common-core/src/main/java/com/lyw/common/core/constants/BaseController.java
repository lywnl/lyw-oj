package com.lyw.common.core.constants;

import com.lyw.common.core.domain.Result;

public class BaseController {
    public Result<Void> toR(int rows) {
        return rows > 0 ? Result.ok() : Result.fail();
    }


    public Result<Void> toR(boolean result) {
        return result ? Result.ok() : Result.fail();
    }
}
