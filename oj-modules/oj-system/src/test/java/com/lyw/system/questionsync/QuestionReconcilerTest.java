package com.lyw.system.questionsync;

import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.lyw.system.questionsync.SyncFixtures.document;

class QuestionReconcilerTest {
    private final QuestionReader reader = mock(QuestionReader.class);
    private final IndexStore index = mock(IndexStore.class);
    private final PositionGuard guard = mock(PositionGuard.class);
    private final QuestionReconciler reconciler = new QuestionReconciler(reader, index, guard, 500);
    private final BinlogPosition old = new BinlogPosition("mysql-bin.000001", 100);
    private final BinlogPosition target = new BinlogPosition("mysql-bin.000001", 300);

    @Test void repairWaitsForSourceBarrierAndDiscardsChangedCandidates() throws Exception {
        when(reader.page(0, 500)).thenReturn(List.of(document(1, "A"), document(2, "B")));
        when(index.get(anyCollection())).thenReturn(Map.of()); when(guard.capture()).thenReturn(target);
        reconciler.step(old, 1000, true, true);
        reconciler.step(old, 2000, true, true); verify(index, never()).write(anyList());
        reconciler.observe(Set.of(1L));
        reconciler.step(target, 3000, true, true);
        verify(index).write(List.of(IndexMutation.put(document(2, "B")))); assertEquals(1, reconciler.repaired());
    }
    @Test void checkReportsWithoutWritingOrCapturingSourceBarrier() throws Exception {
        when(reader.page(0, 500)).thenReturn(List.of(document(1, "A")));
        when(reader.page(1, 500)).thenReturn(List.of()); when(index.get(anyCollection())).thenReturn(Map.of());
        when(index.page(null, 500)).thenReturn(new IndexStore.Page(List.of(2L), List.of("cursor")));
        when(index.page(List.of("cursor"), 500)).thenReturn(new IndexStore.Page(List.of(), null));
        when(reader.find(List.of(2L))).thenReturn(Map.of());
        while (!reconciler.finished()) reconciler.step(null, 1000, false, false);
        assertEquals(2, reconciler.differences()); verify(index, never()).write(anyList()); verifyNoInteractions(guard);
        verify(index, never()).validate();
    }
    @Test void failedDatabasePageCannotBecomeDeleteAll() throws Exception {
        when(reader.page(0, 500)).thenReturn(List.of());
        when(index.page(null, 500)).thenReturn(new IndexStore.Page(List.of(1L), List.of("cursor")));
        when(reader.find(anyCollection())).thenThrow(new SQLException("offline"));
        reconciler.step(null, 1000, true, false);
        assertThrows(SQLException.class, () -> reconciler.step(null, 1000, true, false));
        verify(index, never()).write(anyList());
    }
    @Test void automaticEmptySourceDoesNotBypassManualTruncatePolicy() throws Exception {
        when(reader.page(0, 500)).thenReturn(List.of());
        when(index.page(null, 500)).thenReturn(new IndexStore.Page(List.of(1L), List.of("cursor")));
        when(reader.find(anyCollection())).thenReturn(Map.of()); when(reader.count()).thenReturn(0L);
        reconciler.step(old, 1000, true, true); reconciler.step(old, 2000, true, true);
        assertTrue(reconciler.emptySourceProtected()); assertEquals(1, reconciler.differences()); verify(index, never()).write(anyList());
    }
    @Test void explicitRepairCanRemoveConfirmedExtraDocuments() throws Exception {
        when(reader.page(0, 500)).thenReturn(List.of());
        when(index.page(null, 500)).thenReturn(new IndexStore.Page(List.of(2L), List.of("cursor")));
        when(reader.find(anyCollection())).thenReturn(Map.of());
        reconciler.step(null, 1000, true, false); reconciler.step(null, 2000, true, false);
        verify(index).write(List.of(IndexMutation.delete(2))); assertEquals(1, reconciler.repaired());
    }
    @Test void candidateCanWaitBeyondTwoMinutesButCancelsPageAfterFiveMinutes() throws Exception {
        when(reader.page(0, 500)).thenReturn(List.of());
        when(index.page(null, 500)).thenReturn(new IndexStore.Page(List.of(2L), List.of("pit", 2)));
        when(reader.find(anyCollection())).thenReturn(Map.of()); when(reader.count()).thenReturn(1L);
        when(guard.capture()).thenReturn(target);
        reconciler.step(old, 0, true, true); reconciler.step(old, 1000, true, true);
        reconciler.step(old, 122000, true, true);
        assertFalse(reconciler.finished()); verify(index, never()).closeCursor(any());
        reconciler.step(old, 301000, true, true);
        assertTrue(reconciler.finished()); assertTrue(reconciler.timedOut());
        verify(index).closeCursor(List.of("pit", 2)); verify(index, never()).write(anyList());
        reconciler.step(target, 302000, true, true); verify(index, never()).write(anyList());
    }
    @Test void stoppingDuringDatabaseReadCannotStartRepairWrite() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean stopped = new java.util.concurrent.atomic.AtomicBoolean();
        when(reader.page(0, 500)).thenAnswer(call -> { stopped.set(true); return List.of(document(1, "A")); });
        when(index.get(anyCollection())).thenReturn(Map.of());
        assertThrows(InterruptedException.class, () -> reconciler.step(null, 1000, true, false, stopped::get));
        verify(index, never()).write(anyList());
    }
}
