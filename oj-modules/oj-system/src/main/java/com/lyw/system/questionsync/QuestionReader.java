package com.lyw.system.questionsync;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** 仅供维护/对账，增量处理器不持有此接口。 */
public interface QuestionReader {
    List<QuestionDocument> page(long after, int size) throws SQLException;
    Map<Long, QuestionDocument> find(Collection<Long> ids) throws SQLException;
    long count() throws SQLException;
}
