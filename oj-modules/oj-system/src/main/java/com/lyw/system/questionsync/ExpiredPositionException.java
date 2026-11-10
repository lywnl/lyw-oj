package com.lyw.system.questionsync;

/** 来源已验证但历史文件/偏移不可续传；维护可重建基线，不能自动改位点。 */
public final class ExpiredPositionException extends SyncBlockedException {
    public ExpiredPositionException() { super("binlog 位点已失效，需维护核对基线后重新配置起点"); }
}
