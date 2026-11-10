package com.lyw.system.questionsync;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface IndexStore {
    void validate() throws IOException;
    boolean exists() throws IOException;
    void create() throws IOException;
    void write(List<IndexMutation> changes) throws IOException;
    Map<Long, QuestionDocument> get(Collection<Long> ids) throws IOException;
    record Page(List<Long> ids, List<Object> after) { }
    Page page(List<Object> after, int size) throws IOException;
    default void closeCursor(List<Object> after) throws IOException { }
}
