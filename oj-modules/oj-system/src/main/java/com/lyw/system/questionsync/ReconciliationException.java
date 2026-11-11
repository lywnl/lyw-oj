package com.lyw.system.questionsync;

/** 只携带可公开的固定原因，避免把底层连接信息写入响应。 */
public final class ReconciliationException extends RuntimeException {
    private final int status;
    public ReconciliationException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
    static ReconciliationException unavailable() { return new ReconciliationException(503, "题目同步当前不可用"); }
    static ReconciliationException timeout() { return new ReconciliationException(504, "本轮对账已超时，正在安全结束"); }
}
