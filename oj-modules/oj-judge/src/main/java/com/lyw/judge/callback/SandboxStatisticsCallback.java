package com.lyw.judge.callback;

import com.github.dockerjava.api.model.Statistics;

/** 只补流的生命周期管理，沿用现有最大内存统计口径。 */
public final class SandboxStatisticsCallback extends SandboxStreamCallback<Statistics> {
    private volatile long maxMemory;

    @Override
    public void onNext(Statistics statistics) {
        if (isClosed() || statistics == null || statistics.getMemoryStats() == null) return;
        Long usage = statistics.getMemoryStats().getMaxUsage();
        if (usage != null) maxMemory = Math.max(maxMemory, usage);
    }

    public long getMaxMemory() { return maxMemory; }
}
