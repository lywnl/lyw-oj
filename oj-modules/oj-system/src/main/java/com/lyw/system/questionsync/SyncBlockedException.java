package com.lyw.system.questionsync;

/** 需要人工维护的错误；原因只包含配置/字段名称，不包含题目正文或凭据。 */
public class SyncBlockedException extends RuntimeException {
    public SyncBlockedException(String reason) { super(reason); }
}
