package com.lyw.system.questionsync;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.lyw.system.questionsync.SyncFixtures.*;

class CanalEventMapperTest {
    private final CanalEventMapper mapper = new CanalEventMapper("lywoj");

    @Test void fullImagePreservesLargeIdChineseNullAndUnchangedFields() {
        long id = 9007199254740993L;
        var before = values(id, "旧标题"); var after = values(id, "新标题😀"); after.put("question_case", null);
        var batch = mapper.map(message(event(CanalEntry.EventType.UPDATE, before, after, 100)));
        var doc = batch.changes().get(0).document();
        assertEquals(id, doc.questionId()); assertEquals("新标题😀", doc.title()); assertNull(doc.questionCase());
        assertEquals(1000L, doc.timeLimit()); assertEquals("中文题干\n第二行", doc.content());
        assertEquals("2026-11-10T01:02:03", doc.fields().get("createTime"));
        assertEquals(200, batch.position().offset());
    }
    @Test void latestOperationWinsAndPrimaryKeyChangeDeletesOldIdentity() {
        var batch = mapper.map(message(event(CanalEntry.EventType.INSERT, Map.of(), values(1, "A"), 100),
                event(CanalEntry.EventType.UPDATE, values(1, "A"), values(2, "B"), 200),
                event(CanalEntry.EventType.DELETE, values(2, "B"), Map.of(), 300),
                event(CanalEntry.EventType.INSERT, Map.of(), values(2, "C"), 400)));
        assertEquals(2, batch.changes().size()); assertTrue(batch.changes().get(0).deletion());
        assertEquals(1, batch.changes().get(0).id()); assertEquals("C", batch.changes().get(1).document().title());
        assertEquals(500, batch.position().offset());
    }
    @Test void rawAndDecodedMessagesHaveIdenticalMeaning() {
        var entry = event(CanalEntry.EventType.INSERT, Map.of(), values(1, "A"), 100);
        assertEquals(mapper.map(message(entry)), mapper.map(new Message(7, true, List.of(entry.toByteString()))));
    }
    @Test void missingAndInvalidFieldsBlockInsteadOfFallingBack() {
        var incomplete = values(1, "A"); incomplete.remove("content");
        assertThrows(SyncBlockedException.class, () -> mapper.map(message(event(CanalEntry.EventType.INSERT, Map.of(), incomplete, 100))));
        var invalid = values(1, "A"); invalid.put("difficulty", "bad");
        assertThrows(SyncBlockedException.class, () -> mapper.map(message(event(CanalEntry.EventType.INSERT, Map.of(), invalid, 100))));
    }
    @Test void ddlAnywhereInBatchBlocksBeforeAnyChangesAreReturned() {
        assertThrows(SyncBlockedException.class, () -> mapper.map(message(
                event(CanalEntry.EventType.INSERT, Map.of(), values(1, "A"), 100),
                event(CanalEntry.EventType.TRUNCATE, Map.of(), Map.of(), 200))));
    }
    @Test void otherTableOnlyAdvancesKnownSourcePosition() {
        var original = event(CanalEntry.EventType.INSERT, Map.of(), values(1, "A"), 100);
        var other = original.toBuilder().setHeader(original.getHeader().toBuilder().setTableName("tb_user")).build();
        var batch = mapper.map(message(other)); assertTrue(batch.changes().isEmpty()); assertEquals(200, batch.position().offset());
    }
    @Test void renameAwayAndMultiTableDdlBlockEvenWithDifferentHeader() {
        for (var type : List.of(CanalEntry.EventType.RENAME, CanalEntry.EventType.ERASE)) {
            var original = event(type, Map.of(), Map.of(), 200);
            var ddl = CanalEntry.RowChange.newBuilder().setEventType(type).setIsDdl(true)
                    .setSql(type == CanalEntry.EventType.RENAME
                            ? "RENAME TABLE lywoj.tb_question TO archive.archived_question"
                            : "DROP TABLE lywoj.other_table, lywoj.tb_question").build();
            var entry = original.toBuilder().setHeader(original.getHeader().toBuilder().setSchemaName("archive")
                    .setTableName("other_table")).setStoreValue(ddl.toByteString()).build();
            assertThrows(SyncBlockedException.class, () -> mapper.map(message(
                    event(CanalEntry.EventType.INSERT, Map.of(), values(1, "A"), 100), entry)));
        }
    }
    @Test void statementDmlWithNoRowsCannotBeAcknowledgedAsEmptyWork() {
        var original = event(CanalEntry.EventType.UPDATE, Map.of(), Map.of(), 100);
        var statement = CanalEntry.RowChange.newBuilder().setEventType(CanalEntry.EventType.UPDATE)
                .setSql("UPDATE tb_question SET title='标题'").build();
        assertThrows(SyncBlockedException.class, () -> mapper.map(message(original.toBuilder()
                .setStoreValue(statement.toByteString()).build())));
    }
    @Test void minimalBeforeImageCannotPassEvenWhenItsPrimaryKeyIsEnoughToDelete() {
        assertThrows(SyncBlockedException.class, () -> mapper.map(message(event(CanalEntry.EventType.DELETE,
                Map.of("question_id", "1"), Map.of(), 100))));
        assertThrows(SyncBlockedException.class, () -> mapper.map(message(event(CanalEntry.EventType.UPDATE,
                Map.of("question_id", "1"), values(1, "全部字段恰好都变更"), 100))));
    }
}
