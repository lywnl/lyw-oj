package com.lyw.system.questionsync;

import com.alibaba.otter.canal.protocol.CanalEntry;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.lyw.system.questionsync.SyncFixtures.*;

class QuestionEsSyncRunnerTest {
    private final SyncSource source = mock(SyncSource.class);
    private final IndexStore index = mock(IndexStore.class);
    private final PositionGuard guard = mock(PositionGuard.class);
    private final QuestionReader reader = mock(QuestionReader.class);
    private final SyncProperties properties = properties();
    private final QuestionReconciler reconciler = new QuestionReconciler(reader, index, guard, 500);
    private final QuestionEsSyncRunner runner = new QuestionEsSyncRunner(properties, source, new CanalEventMapper("lywoj"), index, guard, reconciler);

    void setup() throws Exception {
        when(guard.check()).thenReturn(new BinlogPosition("mysql-bin.000001", 4));
        when(source.get(100)).thenReturn(message(event(CanalEntry.EventType.INSERT, Map.of(), values(1, "A"), 100)));
    }
    @Test void incrementNeverReadsQuestionsAndAcknowledgesAfterStorage() throws Exception {
        setup(); runner.tick(1000);
        var order = inOrder(index, source); order.verify(index).validate(); order.verify(source).open();
        order.verify(source).get(100); order.verify(index).write(anyList()); order.verify(source).ack(7);
        verifyNoInteractions(reader); assertEquals(7L, runner.snapshot().get("lastAck"));
    }
    @Test void writeFailureRollsBackWithoutAcknowledging() throws Exception {
        setup(); doThrow(new IOException("partial")).when(index).write(anyList());
        assertThrows(IOException.class, () -> runner.tick(1000)); verify(source).rollback(7); verify(source, never()).ack(anyLong());
        verifyNoInteractions(reader);
    }
    @Test void uncertainAcknowledgementDoesNotAdvanceProgress() throws Exception {
        setup(); doThrow(new IllegalStateException("connection lost")).when(source).ack(7);
        assertThrows(IllegalStateException.class, () -> runner.tick(1000));
        assertEquals(-1L, runner.snapshot().get("lastAck")); verify(index, times(1)).write(anyList());
    }
    @Test void eachReconciliationRoundValidatesOnceAndPagesYieldToConsumption() throws Exception {
        properties.setReconcileIntervalMillis(10);
        when(guard.check()).thenReturn(new BinlogPosition("mysql-bin.000001", 4));
        when(reader.page(0, 500)).thenReturn(List.of(document(1, "A")));
        when(reader.page(1, 500)).thenReturn(List.of());
        when(index.get(List.of(1L))).thenReturn(Map.of(1L, document(1, "A")));
        when(index.page(null, 500)).thenReturn(new IndexStore.Page(List.of(), null));

        runner.tick(1000);
        runner.tick(1010); runner.tick(1011); runner.tick(1012);
        verify(index, times(2)).validate();
        verify(reader).page(0, 500); verify(reader).page(1, 500); verify(index).page(null, 500);
        runner.tick(1022);
        verify(index, times(3)).validate();
        verify(reader, times(2)).page(0, 500);
        verify(source, times(5)).get(100);
        verify(index, never()).write(anyList());
    }
    @Test void failedInitialValidationDoesNotOpenSourceOrReadQuestions() throws Exception {
        setup();
        doThrow(new SyncBlockedException("索引不存在")).when(index).validate();
        assertThrows(SyncBlockedException.class, () -> runner.tick(1000));
        verify(source, never()).open(); verify(index, never()).write(anyList()); verifyNoInteractions(reader);
    }
    @Test void failedNewRoundValidationDoesNotReadOrRepairQuestions() throws Exception {
        properties.setReconcileIntervalMillis(10);
        when(guard.check()).thenReturn(new BinlogPosition("mysql-bin.000001", 4));
        runner.tick(1000);
        doThrow(new SyncBlockedException("映射不兼容")).when(index).validate();
        assertThrows(SyncBlockedException.class, () -> runner.tick(1010));
        verifyNoInteractions(reader); verify(index, never()).write(anyList()); verify(source, never()).ack(anyLong());
    }
    @Test void reconnectionRevalidatesBeforeReopeningSource() throws Exception {
        when(guard.check()).thenReturn(new BinlogPosition("mysql-bin.000001", 4));
        CountDownLatch reopened = new CountDownLatch(1);
        when(source.get(100)).thenThrow(new IllegalStateException("连接中断")).thenAnswer(call -> {
            reopened.countDown(); return null;
        });
        runner.start();
        try {
            assertTrue(reopened.await(4, TimeUnit.SECONDS));
        } finally { runner.stop(); }
        var order = inOrder(index, source);
        order.verify(index).validate(); order.verify(source).open(); order.verify(source).get(100);
        order.verify(index).validate(); order.verify(source).open();
        verify(index, times(2)).validate(); verifyNoInteractions(reader);
    }
    @Test void ddlBlocksThreadAndPreventsAutomaticReconciliation() throws Exception {
        setup(); properties.setReconcileIntervalMillis(1);
        when(source.get(100)).thenReturn(message(event(CanalEntry.EventType.TRUNCATE, Map.of(), Map.of(), 100)));
        runner.start();
        try {
            long end = System.nanoTime() + 2_000_000_000L;
            while (!"BLOCKED".equals(runner.snapshot().get("state")) && System.nanoTime() < end) Thread.sleep(10);
            assertEquals("BLOCKED", runner.snapshot().get("state"));
            verify(index, never()).write(anyList()); verify(source, never()).ack(anyLong()); verifyNoInteractions(reader);
        } finally { runner.stop(); }
    }
    @Test void stopDuringGetDoesNotWriteOrAcknowledgeAndCallbackWaitsForExit() throws Exception {
        assertStopBoundary(false);
    }
    @Test void stopDuringMetadataCheckDoesNotOpenOrWriteAfterCheckReturns() throws Exception {
        assertStopBoundary(true);
        verify(source, never()).open();
    }
    private void assertStopBoundary(boolean blockMetadata) throws Exception {
        setup();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), stopped = new CountDownLatch(1);
        org.mockito.stubbing.Answer<Object> block = call -> {
            entered.countDown();
            // 模拟忽略线程中断、直到 socket/数据库超时才返回的底层调用。
            while (release.getCount() > 0) try { release.await(); } catch (InterruptedException ignored) { }
            return blockMetadata ? new BinlogPosition("mysql-bin.000001", 4)
                    : message(event(CanalEntry.EventType.INSERT, Map.of(), values(1, "A"), 100));
        };
        if (blockMetadata) when(guard.check()).thenAnswer(block); else when(source.get(100)).thenAnswer(block);
        runner.start();
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            runner.stop(stopped::countDown);
            assertFalse(stopped.await(100, TimeUnit.MILLISECONDS)); assertTrue(runner.isRunning());
            release.countDown(); assertTrue(stopped.await(2, TimeUnit.SECONDS));
            assertFalse(runner.isRunning()); verify(index, never()).write(anyList()); verify(source, never()).ack(anyLong());
        } finally { release.countDown(); runner.stop(); }
    }
}
