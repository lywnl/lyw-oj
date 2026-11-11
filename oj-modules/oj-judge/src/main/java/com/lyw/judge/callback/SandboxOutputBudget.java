package com.lyw.judge.callback;

/** 编译与全部用例共享同一个字节预算。 */
public final class SandboxOutputBudget {
    private final long limit;
    private long accepted;
    private boolean exceeded;

    public SandboxOutputBudget(long limit) {
        if (limit <= 0 || limit > Integer.MAX_VALUE - 8L) {
            throw new IllegalArgumentException("输出限制无效");
        }
        this.limit = limit;
    }

    public synchronized int accept(int length) {
        if (length < 0) throw new IllegalArgumentException("输出长度无效");
        if (exceeded) return 0;
        int count = (int) Math.min(length, limit - accepted);
        accepted += count;
        if (count < length) exceeded = true;
        return count;
    }

    public synchronized boolean isExceeded() { return exceeded; }
    public synchronized long getAcceptedBytes() { return accepted; }
}
