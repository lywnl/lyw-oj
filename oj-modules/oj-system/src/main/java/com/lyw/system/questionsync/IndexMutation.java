package com.lyw.system.questionsync;

public record IndexMutation(long id, QuestionDocument document) {
    public IndexMutation {
        if (id <= 0 || (document != null && document.questionId() != id))
            throw new SyncBlockedException("索引操作的题目身份无效");
    }
    public static IndexMutation put(QuestionDocument document) { return new IndexMutation(document.questionId(), document); }
    public static IndexMutation delete(long id) { return new IndexMutation(id, null); }
    public boolean deletion() { return document == null; }
}
