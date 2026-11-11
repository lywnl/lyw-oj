package com.lyw.system.questionsync;

import com.alibaba.otter.canal.protocol.CanalEntry;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static com.lyw.system.questionsync.ReconcileTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReconciliationRequestFlowTest {
    @Test void concurrentRequestsHaveOneWinnerAndNoEarlySuccess() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.reader.page(0, 500)).thenAnswer(call -> { reading.countDown(); awaitUninterruptibly(release); return List.of(); });
            f.start();
            try {
                AtomicInteger accepted = new AtomicInteger(), busy = new AtomicInteger();
                AtomicReference<ReconciliationRequest> winner = new AtomicReference<>();
                List<CompletableFuture<Void>> calls = new ArrayList<>();
                for (int i = 0; i < 12; i++) calls.add(CompletableFuture.runAsync(() -> {
                    try { winner.set(f.runner.requestReconciliation()); accepted.incrementAndGet(); }
                    catch (ReconciliationException failure) { assertEquals(409, failure.status()); busy.incrementAndGet(); }
                }));
                CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
                assertTrue(reading.await(2, TimeUnit.SECONDS));
                assertEquals(1, accepted.get()); assertEquals(11, busy.get());
                assertFalse(winner.get().done());
                release.countDown();
                assertEquals(0, winner.get().await().repaired());
                waitFor(() -> !((Boolean) f.runner.snapshot().get("reconciliationActive")));
                assertEquals(0, f.runner.requestReconciliation().await().differences());
            } finally { release.countDown(); }
        }
    }

    @Test void waitingTimeoutKeepsSlotUntilConsumerCleanupAndDoesNotStopIncrementalThread() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            f.properties.setTimeoutMillis(150);
            CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.reader.page(0, 500)).thenAnswer(call -> { reading.countDown(); awaitUninterruptibly(release); return List.of(SyncFixtures.document(1, "旧值")); });
            f.start();
            try {
                var request = f.runner.requestReconciliation();
                assertTrue(reading.await(2, TimeUnit.SECONDS));
                assertEquals(504, assertThrows(ReconciliationException.class, request::await).status());
                assertEquals(409, assertThrows(ReconciliationException.class, f.runner::requestReconciliation).status());
                assertFalse(request.done());
                release.countDown(); waitFor(request::done);
                assertTrue(f.runner.isRunning()); verify(f.index, never()).write(anyList());
                when(f.reader.page(0, 500)).thenReturn(List.of());
                assertEquals(0, f.runner.requestReconciliation().await().repaired());
            } finally { release.countDown(); }
        }
    }

    @Test void deadlineIsEnforcedEvenWhenNobodyWaitsForTheResponse() throws Exception {
        AtomicLong nanos = new AtomicLong();
        try (var f = new ReconcileTestSupport(nanos::get, System::currentTimeMillis)) {
            CountDownLatch insideGet = new CountDownLatch(1), release = new CountDownLatch(1);
            f.start();
            when(f.source.get(100)).thenAnswer(call -> { insideGet.countDown(); awaitUninterruptibly(release); return null; });
            try {
                assertTrue(insideGet.await(2, TimeUnit.SECONDS));
                var request = f.runner.requestReconciliation();
                nanos.set(TimeUnit.MINUTES.toNanos(11));
                release.countDown(); waitFor(request::done);
                assertEquals(504, assertThrows(ReconciliationException.class, request::await).status());
                verifyNoInteractions(f.reader); assertTrue(f.runner.isRunning());
            } finally { release.countDown(); }
        }
    }

    @Test void interruptingHttpWaitOnlyCancelsThisRound() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.reader.page(0, 500)).thenAnswer(call -> { reading.countDown(); awaitUninterruptibly(release); return List.of(); });
            f.start(); var request = f.runner.requestReconciliation();
            AtomicReference<ReconciliationException> result = new AtomicReference<>();
            Thread waiter = new Thread(() -> { try { request.await(); } catch (ReconciliationException failure) { result.set(failure); } });
            try {
                assertTrue(reading.await(2, TimeUnit.SECONDS)); waiter.start(); waiter.interrupt(); waiter.join(2000);
                assertNotNull(result.get()); assertEquals(503, result.get().status());
                assertFalse(request.done());
                release.countDown(); waitFor(request::done); assertTrue(f.runner.isRunning());
            } finally { release.countDown(); waiter.interrupt(); waiter.join(2000); }
        }
    }

    @Test void stopWaitsForConsumerAndFailsPendingRequestBeforeLifecycleCallback() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            CountDownLatch insideGet = new CountDownLatch(1), release = new CountDownLatch(1), stopped = new CountDownLatch(1);
            f.start();
            when(f.source.get(100)).thenAnswer(call -> { insideGet.countDown(); awaitUninterruptibly(release); return null; });
            try {
                assertTrue(insideGet.await(2, TimeUnit.SECONDS)); var request = f.runner.requestReconciliation();
                f.runner.stop(stopped::countDown);
                assertEquals(503, assertThrows(ReconciliationException.class, f.runner::requestReconciliation).status());
                assertFalse(request.done()); assertFalse(stopped.await(50, TimeUnit.MILLISECONDS));
                release.countDown(); assertTrue(stopped.await(2, TimeUnit.SECONDS)); assertTrue(request.done());
                assertEquals(503, assertThrows(ReconciliationException.class, request::await).status());
                verifyNoInteractions(f.reader);
            } finally { release.countDown(); }
        }
    }

    @Test void transientFailureEndsRoundAndReconnectDoesNotRestartIt() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.reader.page(0, 500)).thenAnswer(call -> { reading.countDown(); awaitUninterruptibly(release); return List.of(); });
            f.start();
            try {
                var request = f.runner.requestReconciliation(); assertTrue(reading.await(2, TimeUnit.SECONDS));
                when(f.source.get(100)).thenThrow(new IllegalStateException("隔离连接故障")).thenReturn(null);
                release.countDown(); assertEquals(500, assertThrows(ReconciliationException.class, request::await).status());
                waitFor(() -> "RUNNING".equals(f.runner.snapshot().get("state")));
                verify(f.reader, times(1)).page(0, 500);
                assertFalse((Boolean) f.runner.snapshot().get("reconciliationActive"));
                assertEquals(0, f.runner.requestReconciliation().await().repaired());
            } finally { release.countDown(); }
        }
    }

    @Test void ddlBlocksAndEndsAnActiveRequest() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.reader.page(0, 500)).thenAnswer(call -> { reading.countDown(); awaitUninterruptibly(release); return List.of(); });
            f.start();
            try {
                var request = f.runner.requestReconciliation(); assertTrue(reading.await(2, TimeUnit.SECONDS));
                when(f.source.get(100)).thenReturn(SyncFixtures.message(SyncFixtures.event(CanalEntry.EventType.TRUNCATE, Map.of(), Map.of(), 100)));
                release.countDown(); assertEquals(503, assertThrows(ReconciliationException.class, request::await).status());
                waitFor(() -> "BLOCKED".equals(f.runner.snapshot().get("state")));
                verify(f.source, never()).ack(anyLong()); verify(f.index, never()).write(anyList());
            } finally { release.countDown(); }
        }
    }

    @Test void candidateTimeoutIsReportedAsFailure() throws Exception {
        AtomicLong wall = new AtomicLong(1000);
        try (var f = new ReconcileTestSupport(System::nanoTime, wall::get)) {
            CountDownLatch captured = new CountDownLatch(1);
            when(f.reader.page(0, 500)).thenReturn(List.of(SyncFixtures.document(1, "旧值")));
            when(f.positions.capture()).thenAnswer(call -> { captured.countDown(); return new BinlogPosition("mysql-bin.000001", 9999); });
            f.start(); var request = f.runner.requestReconciliation(); assertTrue(captured.await(2, TimeUnit.SECONDS));
            wall.set(301001); assertEquals(504, assertThrows(ReconciliationException.class, request::await).status());
            verify(f.index, never()).write(anyList()); assertTrue(f.runner.isRunning());
        }
    }

    @Test void emptySourceProtectionIsNotReportedAsSuccess() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            var cursor = List.<Object>of("isolated-pit");
            when(f.index.page(null, 500)).thenReturn(new IndexStore.Page(List.of(99L), cursor));
            when(f.index.page(cursor, 500)).thenReturn(new IndexStore.Page(List.of(), null));
            when(f.reader.find(List.of(99L))).thenReturn(Map.of()); when(f.reader.count()).thenReturn(0L);
            f.start();
            assertEquals(500, assertThrows(ReconciliationException.class, () -> f.runner.requestReconciliation().await()).status());
            verify(f.index, never()).write(anyList()); assertTrue(f.runner.isRunning());
        }
    }

    @Test void cursorCleanupFailureFinishesFutureButKeepsInstanceIsolated() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            var cursor = List.<Object>of("isolated-pit");
            CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
            when(f.index.page(null, 500)).thenReturn(new IndexStore.Page(List.of(1L), cursor));
            when(f.reader.find(List.of(1L))).thenAnswer(call -> { reading.countDown(); awaitUninterruptibly(release); return Map.of(); });
            doThrow(new IOException("清理故障注入")).when(f.index).closeCursor(cursor);
            f.start();
            try {
                var request = f.runner.requestReconciliation(); assertTrue(reading.await(2, TimeUnit.SECONDS));
                request.cancel(ReconciliationException.timeout()); release.countDown();
                assertEquals(500, assertThrows(ReconciliationException.class, request::await).status());
                waitFor(() -> !f.runner.isRunning());
                assertEquals("BLOCKED", f.runner.snapshot().get("state"));
                assertEquals(true, f.runner.snapshot().get("reconciliationActive"));
                assertEquals(true, f.runner.snapshot().get("reconciliationCleanupFailed"));
                assertEquals(503, assertThrows(ReconciliationException.class, f.runner::requestReconciliation).status());
                assertThrows(SyncBlockedException.class, f.runner::start);
            } finally { release.countDown(); }
        }
    }

    @Test void slotRemainsBusyWhileSuccessfulCursorCleanupIsBlocked() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            var cursor = List.<Object>of("isolated-pit");
            CountDownLatch reading = new CountDownLatch(1), releaseRead = new CountDownLatch(1);
            CountDownLatch cleaning = new CountDownLatch(1), releaseClean = new CountDownLatch(1);
            when(f.index.page(null, 500)).thenReturn(new IndexStore.Page(List.of(1L), cursor));
            when(f.reader.find(List.of(1L))).thenAnswer(call -> { reading.countDown(); awaitUninterruptibly(releaseRead); return Map.of(); });
            doAnswer(call -> { cleaning.countDown(); awaitUninterruptibly(releaseClean); return null; }).when(f.index).closeCursor(cursor);
            f.start();
            try {
                var request = f.runner.requestReconciliation(); assertTrue(reading.await(2, TimeUnit.SECONDS));
                request.cancel(ReconciliationException.timeout()); releaseRead.countDown();
                assertTrue(cleaning.await(2, TimeUnit.SECONDS)); assertFalse(request.done());
                assertEquals(409, assertThrows(ReconciliationException.class, f.runner::requestReconciliation).status());
                releaseClean.countDown(); assertEquals(504, assertThrows(ReconciliationException.class, request::await).status());
                assertTrue(f.runner.isRunning());
            } finally { releaseRead.countDown(); releaseClean.countDown(); }
        }
    }

    @Test void disabledAndStoppedConsumersRejectRequestsWithoutReadingQuestions() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            assertEquals(503, assertThrows(ReconciliationException.class, f.runner::requestReconciliation).status());
            f.properties.setEnabled(false); f.start();
            assertEquals(503, assertThrows(ReconciliationException.class, f.runner::requestReconciliation).status());
            verifyNoInteractions(f.reader);
        }
    }

    @Test void cleanupFailureBeforeFirstPageReturnsStillIsolatesRunner() throws Exception {
        try (var f = new ReconcileTestSupport()) {
            when(f.index.page(null, 500)).thenThrow(new CursorCleanupException());
            f.start(); var request = f.runner.requestReconciliation();
            assertEquals(500, assertThrows(ReconciliationException.class, request::await).status());
            waitFor(() -> !f.runner.isRunning());
            assertEquals(true, f.runner.snapshot().get("reconciliationCleanupFailed"));
            assertEquals(true, f.runner.snapshot().get("reconciliationActive"));
            assertEquals(503, assertThrows(ReconciliationException.class, f.runner::requestReconciliation).status());
        }
    }
}
