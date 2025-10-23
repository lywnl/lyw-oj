package com.lyw.common.core.domain;

import com.lyw.common.core.enums.ResultCode;
import lombok.Getter;
import lombok.Setter;

/**
 * 统一返回结果
 */
@Getter
@Setter
public class Result<T> {
    private int code;
    private String msg;
    private T data;

    public static <T> Result<T> ok() {
        return assembleResult(null, ResultCode.SUCCESS);
    }

    public static <T> Result<T> ok(T data) {
        return assembleResult(data, ResultCode.SUCCESS);
    }

    public static <T> Result<T> fail() {
        return assembleResult(null, ResultCode.FAILED);
    }

    public static <T> Result<T> fail(int code, String msg) {
        return assembleResult(code, msg, null);
    }

    /**
     * 指定错误码
     *
     * @param resultCode 指定错误码
     * @param <T>
     * @return
     */
    public static <T> Result<T> fail(ResultCode resultCode) {
        return assembleResult(null, resultCode);
    }

    private static <T> Result assembleResult(T data, ResultCode resultCode) {
        Result<T> r = new Result<>();
        r.setCode(resultCode.getCode());
        r.setData(data);
        r.setMsg(resultCode.getMsg());
        return r;
    }

    private static <T> Result<T> assembleResult(int code, String msg, T data) {
        Result<T> r = new Result<>();
        r.setCode(code);
        r.setData(data);
        r.setMsg(msg);
        return r;
    }
}
