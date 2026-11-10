package com.lyw.system.questionsync;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/** 不使用 TCP 消费接口探测位点，避免 check 隐含订阅或回滚活跃批次。 */
public final class SourcePositionGuard implements PositionGuard {
    private final SyncProperties properties;
    private final DataSource business;
    private boolean metadataObserved;
    private boolean validCursorObserved;
    public SourcePositionGuard(SyncProperties properties, DataSource business) {
        this.properties = properties; this.business = business;
    }

    private Connection source() throws SQLException {
        Properties options = new Properties();
        options.setProperty("user", properties.getSourceUsername());
        options.setProperty("password", properties.getSourcePassword());
        options.setProperty("connectTimeout", Integer.toString(properties.getTimeoutMillis()));
        options.setProperty("socketTimeout", Integer.toString(properties.getTimeoutMillis()));
        return DriverManager.getConnection(properties.getSourceJdbcUrl(), options);
    }

    @Override public BinlogPosition check() throws Exception {
        properties.validate();
        try (Connection source = source(); Connection app = business.getConnection()) {
            if (!properties.getDatabase().equals(source.getCatalog()) || !properties.getDatabase().equals(app.getCatalog()))
                throw new SyncBlockedException("业务库、复制源和同步订阅库不一致");
            String sourceId = variable(source, "server_uuid");
            if (!sourceId.equals(variable(app, "server_uuid"))) throw new SyncBlockedException("复制源与业务数据源 server_uuid 不一致");
            if (!"1".equals(variable(source, "log_bin")) || !"ROW".equalsIgnoreCase(variable(source, "binlog_format"))
                    || !"FULL".equalsIgnoreCase(variable(source, "binlog_row_image")))
                throw new SyncBlockedException("复制源必须启用 binlog ROW/FULL");
            BinlogPosition cursor = readCursor();
            Map<String, Long> logs = new HashMap<>();
            try (var statement = source.createStatement()) {
                statement.setQueryTimeout(seconds());
                try (var rows = statement.executeQuery("SHOW BINARY LOGS")) {
                    while (rows.next()) logs.put(rows.getString(1), rows.getLong(2));
                }
            }
            Long size = logs.get(cursor.file());
            if (size == null || cursor.offset() > size) throw new ExpiredPositionException();
            return cursor;
        }
    }

    String variable(Connection connection, String name) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.setQueryTimeout(seconds());
            try (var result = statement.executeQuery("SELECT @@GLOBAL." + name)) {
                if (!result.next()) throw new SQLException("源元信息未返回结果");
                return result.getString(1);
            }
        }
    }

    private int seconds() { return Math.max(1, (properties.getTimeoutMillis() + 999) / 1000); }

    @Override public BinlogPosition capture() throws SQLException {
        try (Connection connection = source(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(seconds());
            try (var result = statement.executeQuery("SHOW MASTER STATUS")) {
                if (!result.next()) throw new SyncBlockedException("源未返回有效 binlog 起点");
                return new BinlogPosition(result.getString(1), result.getLong(2));
            }
        }
    }

    BinlogPosition readCursor() throws IOException {
        Path state = Path.of(properties.getStateDirectory());
        if (!Files.readAttributes(state, BasicFileAttributes.class).isDirectory())
            throw new IOException("Canal 共享位点路径不是目录");
        Path metadata = state.resolve(properties.getDestination()).resolve("meta.dat");
        if (!exists(metadata)) {
            if (metadataObserved || exists(state.resolve("metadata-established")))
                throw new SyncBlockedException("既有消费元数据丢失，不能作为首次启动处理");
            return initialCursor(state);
        }
        metadataObserved = true;
        try {
            JSONObject root = JSON.parseObject(Files.readString(metadata));
            if (root == null || !properties.getDestination().equals(root.getString("destination")))
                throw new IOException("消费元数据尚不完整或身份不匹配");
            JSONArray clients = root.getJSONArray("clientDatas");
            if (clients != null) for (int i = 0; i < clients.size(); i++) {
                JSONObject item = clients.getJSONObject(i);
                JSONObject identity = item.getJSONObject("clientIdentity");
                if (identity != null && identity.getIntValue("clientId") == properties.getClientId()
                        && properties.getDestination().equals(identity.getString("destination"))) {
                    JSONObject cursor = item.getJSONObject("cursor");
                    // 首次 subscribe 会持久化身份，但 fastjson2 默认省略 null 游标；尚无 ACK 时沿用已核对的 P。
                    // 已有有效游标、建立标记、损坏 JSON 或身份不匹配均不能走这条分支。
                    if (cursor == null) return initialCursor(state);
                    JSONObject position = cursor == null ? null : cursor.getJSONObject("postion");
                    if (position == null) break;
                    BinlogPosition result;
                    try { result = new BinlogPosition(position.getString("journalName"), position.getLongValue("position")); }
                    catch (SyncBlockedException incomplete) { throw new IOException("消费游标字段不完整，等待文件刷新后重试"); }
                    validCursorObserved = true;
                    return result;
                }
            }
            throw new IOException("消费元数据尚未包含完整游标，禁止回退起点");
        } catch (SyncBlockedException blocked) {
            throw blocked;
        } catch (RuntimeException partial) {
            throw new IOException("消费元数据读取不完整，等待文件刷新后重试");
        }
    }

    private BinlogPosition initialCursor(Path state) throws IOException {
        if (validCursorObserved || exists(state.resolve("metadata-established")))
            throw new SyncBlockedException("既有消费游标丢失，不能回退首次起点");
        if (properties.getInitialFile() == null || properties.getInitialFile().isBlank())
            throw new SyncBlockedException("首次启动必须提供经基线核对的显式起点 P");
        return new BinlogPosition(properties.getInitialFile(), properties.getInitialPosition());
    }

    private boolean exists(Path path) throws IOException {
        try { Files.readAttributes(path, BasicFileAttributes.class); return true; }
        catch (NoSuchFileException missing) { return false; }
        // 无权限或 I/O 故障必须重试，不能被 Files.exists 的 false 混同为首次启动。
    }
}
