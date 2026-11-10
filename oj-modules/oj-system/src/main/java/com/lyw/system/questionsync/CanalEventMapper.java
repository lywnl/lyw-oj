package com.lyw.system.questionsync;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.Message;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 纯转换器：无数据库、网络或索引依赖。先验证整批，再允许写入。 */
public final class CanalEventMapper {
    private final String database;
    private static final List<String> FIELDS = List.of("question_id", "title", "difficulty", "time_limit", "space_limit",
            "content", "question_case", "main_fuc", "default_code", "create_time");

    public CanalEventMapper(String database) { this.database = database; }

    public record Batch(List<IndexMutation> changes, Set<Long> changedIds, BinlogPosition position) { }

    public Batch map(Message message) {
        List<CanalEntry.Entry> entries = new ArrayList<>();
        try {
            if (message.isRaw()) {
                for (var bytes : message.getRawEntries()) entries.add(CanalEntry.Entry.parseFrom(bytes));
            } else entries.addAll(message.getEntries());
            Map<Long, IndexMutation> changes = new LinkedHashMap<>();
            BinlogPosition position = null;
            for (var entry : entries) {
                BinlogPosition end = BinlogPosition.endOf(entry.getHeader());
                if (end != null && (position == null || end.compareTo(position) > 0)) position = end;
                if (entry.getEntryType() != CanalEntry.EntryType.ROWDATA) continue;
                var rowChange = CanalEntry.RowChange.parseFrom(entry.getStoreValue());
                var type = rowChange.getEventType();
                // 源已作单表过滤，但多表 DROP / RENAME 的 header 不一定是目标表。
                // 无法证明无关的 QUERY 类 DDL 也保守阻塞，不能将其作为追平证据。
                if (rowChange.getIsDdl() || type == CanalEntry.EventType.TRUNCATE)
                    throw new SyncBlockedException("目标表出现 DDL/TRUNCATE 或不支持的事件，需人工维护");
                if (!database.equals(entry.getHeader().getSchemaName()) || !SyncProperties.TABLE.equals(entry.getHeader().getTableName()))
                    continue;
                if (!(type == CanalEntry.EventType.INSERT || type == CanalEntry.EventType.UPDATE || type == CanalEntry.EventType.DELETE))
                    throw new SyncBlockedException("目标表出现不支持的事件，需人工维护");
                if (rowChange.getRowDatasCount() == 0)
                    throw new SyncBlockedException("题目 DML 缺少行数据，可能使用了 STATEMENT binlog");
                for (var row : rowChange.getRowDatasList()) {
                    if (type == CanalEntry.EventType.DELETE) {
                        var before = columns(row.getBeforeColumnsList()); requireFullImage(before);
                        long id = id(before);
                        changes.put(id, IndexMutation.delete(id));
                    } else {
                        QuestionDocument document = document(columns(row.getAfterColumnsList()));
                        if (type == CanalEntry.EventType.UPDATE) {
                            var before = columns(row.getBeforeColumnsList()); requireFullImage(before);
                            long oldId = id(before);
                            if (oldId != document.questionId()) changes.put(oldId, IndexMutation.delete(oldId));
                        }
                        changes.put(document.questionId(), IndexMutation.put(document));
                    }
                }
            }
            return new Batch(List.copyOf(changes.values()), Set.copyOf(changes.keySet()), position);
        } catch (InvalidProtocolBufferException | IllegalArgumentException | ArithmeticException invalid) {
            throw new SyncBlockedException("Canal 完整行事件无法解析");
        }
    }

    private Map<String, String> columns(List<CanalEntry.Column> columns) {
        Map<String, String> values = new HashMap<>();
        for (var column : columns) {
            if (values.containsKey(column.getName())) throw new SyncBlockedException("行事件包含重复列名");
            values.put(column.getName(), column.getIsNull() ? null : column.getValue());
        }
        return values;
    }

    private long id(Map<String, String> values) {
        long id = Long.parseLong(values.get("question_id"));
        if (id <= 0) throw new SyncBlockedException("question_id 必须是正整数");
        return id;
    }

    private QuestionDocument document(Map<String, String> values) {
        requireFullImage(values);
        try {
            return new QuestionDocument(id(values), values.get("title"), Integer.valueOf(values.get("difficulty")),
                    Long.valueOf(values.get("time_limit")), Long.valueOf(values.get("space_limit")), values.get("content"),
                    values.get("question_case"), values.get("main_fuc"), values.get("default_code"),
                    LocalDateTime.parse(values.get("create_time").replace(' ', 'T')));
        } catch (RuntimeException invalid) {
            if (invalid instanceof SyncBlockedException blocked) throw blocked;
            throw new SyncBlockedException("完整行字段类型无法转换");
        }
    }

    private void requireFullImage(Map<String, String> values) {
        for (String field : FIELDS) if (!values.containsKey(field))
            throw new SyncBlockedException("非完整行事件，缺少列 " + field);
    }
}
