package com.lyw.system.questionsync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static com.lyw.system.questionsync.SyncFixtures.properties;

class SourcePositionGuardTest {
    @TempDir Path state;

    private SourcePositionGuard guard() {
        SyncProperties p = properties(); p.setStateDirectory(state.toString());
        p.setInitialFile("mysql-bin.000001"); p.setInitialPosition(157);
        return new SourcePositionGuard(p, mock(DataSource.class));
    }
    private void metadata(String cursor) throws IOException {
        Path folder = state.resolve("question-sync"); Files.createDirectories(folder);
        Files.writeString(folder.resolve("meta.dat"), "{\"destination\":\"question-sync\",\"clientDatas\":[{"
                + "\"clientIdentity\":{\"clientId\":1001,\"destination\":\"question-sync\"},\"cursor\":" + cursor + "}]}");
    }
    @Test void firstSubscriptionWithNullCursorRetainsExplicitStart() throws Exception {
        SourcePositionGuard guard = guard();
        assertEquals(157, guard.readCursor().offset());
        metadata("null");
        assertEquals(157, guard.readCursor().offset());
        Path file = state.resolve("question-sync/meta.dat");
        Files.writeString(file, Files.readString(file).replace(",\"cursor\":null", ""));
        assertEquals(157, guard.readCursor().offset());
        metadata("{\"postion\":{\"journalName\":\"mysql-bin.000002\",\"position\":300}}");
        assertEquals(new BinlogPosition("mysql-bin.000002", 300), guard.readCursor());
        metadata("null");
        assertThrows(SyncBlockedException.class, guard::readCursor);
    }
    @Test void establishedMarkerPreventsFallbackAcrossProcessRestart() throws Exception {
        Files.writeString(state.resolve("metadata-established"), "");
        assertThrows(SyncBlockedException.class, guard()::readCursor);
        metadata("null"); assertThrows(SyncBlockedException.class, guard()::readCursor);
    }
    @Test void corruptAndForeignMetadataNeverFallsBackToStart() throws Exception {
        SourcePositionGuard guard = guard(); metadata("{}");
        assertThrows(IOException.class, guard::readCursor);
        metadata("{\"postion\":{\"journalName\":\"mysql-bin.000001\",\"position\":0}}");
        assertThrows(IOException.class, guard::readCursor);
        Files.writeString(state.resolve("question-sync/meta.dat"), "{\"destination\":");
        assertThrows(IOException.class, guard::readCursor);
        metadata("null");
        Path file = state.resolve("question-sync/meta.dat");
        Files.writeString(file, Files.readString(file).replace("1001", "1002"));
        assertThrows(IOException.class, guard::readCursor);
        Files.delete(file);
        assertThrows(SyncBlockedException.class, guard::readCursor);
    }
    @Test void missingSharedDirectoryCannotBeTreatedAsAFirstValidSubscription() throws Exception {
        SyncProperties p = properties(); p.setStateDirectory(state.resolve("not-mounted").toString());
        p.setInitialFile("mysql-bin.000001"); p.setInitialPosition(157);
        SourcePositionGuard guard = new SourcePositionGuard(p, mock(DataSource.class));
        assertThrows(IOException.class, guard::readCursor);
    }
}
