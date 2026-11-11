package com.lyw.api.reconcile;

/** 实际完成一轮对账后的统计，不表示已接受触发。 */
public record ReconcileResult(long differences, long repaired, long durationMillis) { }
