package com.lyw.system.questionsync;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class JdbcQuestionReader implements QuestionReader {
    private static final String COLUMNS = "question_id,title,difficulty,time_limit,space_limit,content,question_case,main_fuc,default_code,create_time";
    private final DataSource dataSource;
    private final int seconds;
    public JdbcQuestionReader(DataSource dataSource, int timeoutMillis) {
        this.dataSource = dataSource; this.seconds = Math.max(1, (timeoutMillis + 999) / 1000);
    }
    @Override public List<QuestionDocument> page(long after, int size) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM tb_question WHERE question_id>? ORDER BY question_id LIMIT ?")) {
            statement.setQueryTimeout(seconds); statement.setLong(1, after); statement.setInt(2, size);
            return rows(statement);
        }
    }
    @Override public Map<Long, QuestionDocument> find(Collection<Long> ids) throws SQLException {
        if (ids.isEmpty()) return Map.of();
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM tb_question WHERE question_id IN (" + placeholders + ")")) {
            statement.setQueryTimeout(seconds);
            int index = 1; for (Long id : ids) statement.setLong(index++, id);
            Map<Long, QuestionDocument> result = new HashMap<>();
            for (QuestionDocument document : rows(statement)) result.put(document.questionId(), document);
            return result;
        }
    }
    @Override public long count() throws SQLException {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.setQueryTimeout(seconds);
            try (var result = statement.executeQuery("SELECT COUNT(*) FROM tb_question")) {
                if (!result.next()) throw new SQLException("题目计数未返回结果");
                return result.getLong(1);
            }
        }
    }
    private List<QuestionDocument> rows(PreparedStatement statement) throws SQLException {
        List<QuestionDocument> result = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.add(new QuestionDocument(rows.getLong("question_id"), rows.getString("title"),
                    rows.getInt("difficulty"), rows.getLong("time_limit"), rows.getLong("space_limit"), rows.getString("content"),
                    rows.getString("question_case"), rows.getString("main_fuc"), rows.getString("default_code"),
                    rows.getObject("create_time", LocalDateTime.class)));
        }
        return result;
    }
}
