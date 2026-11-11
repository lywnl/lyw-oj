package com.lyw.system.questionsync;

import com.lyw.api.reconcile.QuestionReconcileProperties;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

final class ReconcileTestSupport implements AutoCloseable {
    static final String TOKEN = "isolated-reconciliation-test-token-32";
    final SyncProperties sync = SyncFixtures.properties();
    final QuestionReconcileProperties properties = settings();
    final SyncSource source = mock(SyncSource.class);
    final IndexStore index = mock(IndexStore.class);
    final PositionGuard positions = mock(PositionGuard.class);
    final QuestionReader reader = mock(QuestionReader.class);
    final QuestionReconciler reconciler = new QuestionReconciler(reader, index, positions, 500);
    final QuestionEsSyncRunner runner;

    ReconcileTestSupport() throws Exception { this(System::nanoTime, System::currentTimeMillis); }
    ReconcileTestSupport(LongSupplier nanos, LongSupplier millis) throws Exception {
        sync.setIdleMillis(5);
        when(positions.check()).thenReturn(new BinlogPosition("mysql-bin.000001", 4));
        when(positions.capture()).thenReturn(new BinlogPosition("mysql-bin.000001", 4));
        when(reader.page(0, 500)).thenReturn(List.of());
        when(index.page(null, 500)).thenReturn(new IndexStore.Page(List.of(), null));
        runner = new QuestionEsSyncRunner(sync, source, new CanalEventMapper("lywoj"), index, positions,
                reconciler, properties, nanos, millis);
    }
    static QuestionReconcileProperties settings() {
        var settings = new QuestionReconcileProperties();
        settings.setEnabled(true); settings.setInternalToken(TOKEN);
        return settings;
    }
    void start() throws Exception {
        runner.start(); waitFor(() -> "RUNNING".equals(runner.snapshot().get("state")));
    }
    static void waitFor(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "等待测试状态超时");
    }
    static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() != 0) {
            try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("测试未释放阻塞点"); }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    @Override public void close() { runner.stop(); }
}
