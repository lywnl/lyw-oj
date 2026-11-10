package com.lyw.system.questionsync;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SyncFixtures {
    static Map<String, String> values(long id, String title) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("question_id", Long.toString(id)); fields.put("title", title); fields.put("difficulty", "2");
        fields.put("time_limit", "1000"); fields.put("space_limit", "1024000"); fields.put("content", "中文题干\n第二行");
        fields.put("question_case", ""); fields.put("main_fuc", "入口"); fields.put("default_code", "public class Solution {}");
        fields.put("create_time", "2026-11-10 01:02:03");
        return fields;
    }
    static List<CanalEntry.Column> columns(Map<String, String> fields) {
        List<CanalEntry.Column> result = new ArrayList<>();
        fields.forEach((name, value) -> result.add(CanalEntry.Column.newBuilder().setName(name)
                .setValue(value == null ? "" : value).setIsNull(value == null).setIsKey("question_id".equals(name))
                .setUpdated("title".equals(name)).build()));
        return result;
    }
    static CanalEntry.Entry event(CanalEntry.EventType type, Map<String, String> before, Map<String, String> after, long offset) {
        var row = CanalEntry.RowData.newBuilder().addAllBeforeColumns(columns(before)).addAllAfterColumns(columns(after));
        var change = CanalEntry.RowChange.newBuilder().setEventType(type).setIsDdl(type == CanalEntry.EventType.TRUNCATE
                || type == CanalEntry.EventType.ALTER).addRowDatas(row);
        return CanalEntry.Entry.newBuilder().setEntryType(CanalEntry.EntryType.ROWDATA)
                .setHeader(CanalEntry.Header.newBuilder().setSchemaName("lywoj").setTableName("tb_question")
                        .setLogfileName("mysql-bin.000001").setLogfileOffset(offset).setEventLength(100))
                .setStoreValue(change.build().toByteString()).build();
    }
    static Message message(CanalEntry.Entry... entries) { return new Message(7, List.of(entries)); }
    static QuestionDocument document(long id, String title) {
        return new QuestionDocument(id, title, 2, 1000L, 1024000L, "中文题干\n第二行", "", "入口",
                "public class Solution {}", LocalDateTime.of(2026, 11, 10, 1, 2, 3));
    }
    static SyncProperties properties() {
        SyncProperties p = new SyncProperties(); p.setDatabase("lywoj"); p.setEnabled(true); p.setIdleMillis(10);
        p.setSourceJdbcUrl("jdbc:mysql://localhost/lywoj"); p.setSourceUsername("test"); p.setSourcePassword("test");
        p.setStateDirectory("unused"); p.setReconcileIntervalMillis(0); return p;
    }
}
