package com.lyw.system.questionsync;

import com.alibaba.otter.canal.protocol.Message;

public interface SyncSource extends AutoCloseable {
    void open();
    Message get(int size);
    void ack(long batchId);
    void rollback(long batchId);
    @Override void close();
}
