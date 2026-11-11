package com.lyw.system.questionsync;

import com.lyw.api.reconcile.ReconcileResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Future 不向调用方暴露写权限；只有消费线程可以完成请求或释放所属槽。 */
public final class ReconciliationRequest {
    private final CompletableFuture<ReconcileResult> future = new CompletableFuture<>();
    private final AtomicReference<ReconciliationException> cancellation = new AtomicReference<>();
    private final LongSupplier clock;
    private final long started;
    private final long budgetNanos;
    boolean startedByConsumer;

    ReconciliationRequest(int timeoutMillis, LongSupplier clock) {
        this.clock = clock;
        this.started = clock.getAsLong();
        this.budgetNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    public ReconcileResult await() {
        try {
            return future.get(Math.max(1, budgetNanos - (clock.getAsLong() - started)), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timedOut) {
            cancel(ReconciliationException.timeout());
            throw cancellation.get();
        } catch (InterruptedException interrupted) {
            cancel(new ReconciliationException(503, "对账等待已中断，正在安全结束"));
            Thread.currentThread().interrupt();
            throw cancellation.get();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof ReconciliationException known) throw known;
            throw new ReconciliationException(500, "本轮对账未成功完成");
        }
    }

    void cancel(ReconciliationException reason) { cancellation.compareAndSet(null, reason); }
    ReconciliationException cancellation() {
        if (clock.getAsLong() - started >= budgetNanos) cancel(ReconciliationException.timeout());
        return cancellation.get();
    }
    boolean cancelled() { return cancellation() != null; }
    long elapsedMillis() { return TimeUnit.NANOSECONDS.toMillis(Math.max(0, clock.getAsLong() - started)); }
    void succeed(ReconcileResult result) { future.complete(result); }
    void fail(ReconciliationException failure) { future.completeExceptionally(failure); }
    boolean done() { return future.isDone(); }
}
