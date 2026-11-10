package com.lyw.system.questionsync;

import com.alibaba.fastjson2.JSONObject;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 索引投影只包含既有字段，保留 mainFuc 的历史命名。 */
public record QuestionDocument(Long questionId, String title, Integer difficulty, Long timeLimit,
        Long spaceLimit, String content, String questionCase, String mainFuc, String defaultCode,
        LocalDateTime createTime) {
    public static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public QuestionDocument {
        if (questionId == null || questionId <= 0 || title == null || difficulty == null || timeLimit == null
                || spaceLimit == null || content == null || mainFuc == null || defaultCode == null || createTime == null)
            throw new SyncBlockedException("题目投影缺少必需字段");
        if (createTime.getNano() != 0) throw new SyncBlockedException("create_time 精度不兼容既有秒级索引映射");
    }

    public Map<String, Object> fields() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("questionId", questionId); result.put("title", title); result.put("difficulty", difficulty);
        result.put("timeLimit", timeLimit); result.put("spaceLimit", spaceLimit); result.put("content", content);
        result.put("questionCase", questionCase); result.put("mainFuc", mainFuc); result.put("defaultCode", defaultCode);
        result.put("createTime", TIME.format(createTime));
        return result;
    }

    public static QuestionDocument fromIndex(JSONObject value) {
        try {
            return new QuestionDocument(value.getLong("questionId"), value.getString("title"), value.getInteger("difficulty"),
                    value.getLong("timeLimit"), value.getLong("spaceLimit"), value.getString("content"),
                    value.getString("questionCase"), value.getString("mainFuc"), value.getString("defaultCode"),
                    LocalDateTime.parse(value.getString("createTime")));
        } catch (RuntimeException invalid) {
            throw new SyncBlockedException("已有 ES 文档不符合题目投影契约");
        }
    }
}
