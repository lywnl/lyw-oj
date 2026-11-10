package com.lyw.system.questionsync;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.lyw.system.questionsync.SyncFixtures.*;

class QuestionSyncMaintenanceRunnerTest {
    private final SyncProperties properties = properties();
    private final PositionGuard positions = mock(PositionGuard.class);
    private final IndexStore index = mock(IndexStore.class);
    private final QuestionReader reader = mock(QuestionReader.class);
    private final QuestionReconciler reconciler = new QuestionReconciler(reader, index, positions, 500);

    private QuestionSyncMaintenanceRunner runner(String mode) {
        properties.setEnabled(false); properties.setMode(mode);
        return new QuestionSyncMaintenanceRunner(properties, positions, index, reconciler, new GenericApplicationContext());
    }
    private void differences() throws Exception {
        when(positions.check()).thenReturn(new BinlogPosition("mysql-bin.000001", 4));
        when(positions.capture()).thenReturn(new BinlogPosition("mysql-bin.000001", 4));
        when(index.exists()).thenReturn(true);
        when(reader.page(0, 500)).thenReturn(List.of(document(1, "最新题目")));
        when(reader.page(1, 500)).thenReturn(List.of());
        when(index.get(List.of(1L))).thenReturn(Map.of());
        when(index.page(null, 500)).thenReturn(new IndexStore.Page(List.of(), null));
    }
    @Test void unconfirmedRepairIsRejectedBeforeAnyReadOrWrite() {
        var runner = runner("repair");
        assertThrows(SyncBlockedException.class, runner::execute);
        verifyNoInteractions(positions, index, reader);
    }
    @Test void checkValidatesOnceAcrossPagesAndDoesNotRepair() throws Exception {
        differences();
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            properties.setPort(server.getLocalPort());
            assertEquals(2, runner("check").execute());
        }
        verify(index).validate(); verify(index, never()).write(anyList()); verify(index, never()).create();
        assertEquals(1, reconciler.differences());
    }
    @Test void confirmedRepairUsesExplicitConfirmationWithoutLocalGate() throws Exception {
        differences(); properties.setMaintenanceConfirmed(true);
        assertEquals(0, runner("repair").execute());
        verify(index).validate(); verify(index).write(List.of(IndexMutation.put(document(1, "最新题目"))));
        assertEquals(1, reconciler.repaired());
    }
    @Test void validationFailurePreventsMaintenanceReadsAndRepairs() throws Exception {
        when(index.exists()).thenReturn(true);
        doThrow(new SyncBlockedException("映射不兼容")).when(index).validate();
        properties.setMaintenanceConfirmed(true);
        assertThrows(SyncBlockedException.class, () -> runner("repair").execute());
        verifyNoInteractions(reader); verify(index, never()).write(anyList());
    }
}
