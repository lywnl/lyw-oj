package com.lyw.system.questionsync;

import com.alibaba.otter.canal.protocol.CanalEntry;

public record BinlogPosition(String file, long offset) implements Comparable<BinlogPosition> {
    public BinlogPosition {
        if (file == null || !file.matches("[a-zA-Z0-9._-]+\\.[0-9]+") || offset < 4)
            throw new SyncBlockedException("binlog 文件或偏移无效");
    }

    public static BinlogPosition endOf(CanalEntry.Header header) {
        if (header.getLogfileName().isBlank() || header.getEventLength() <= 0) return null;
        return new BinlogPosition(header.getLogfileName(), Math.addExact(header.getLogfileOffset(), header.getEventLength()));
    }

    @Override public int compareTo(BinlogPosition other) {
        int split = file.lastIndexOf('.');
        int otherSplit = other.file.lastIndexOf('.');
        if (!file.substring(0, split).equals(other.file.substring(0, otherSplit)))
            throw new SyncBlockedException("binlog 文件前缀改变，需重新核对来源");
        long number = Long.parseLong(file.substring(split + 1));
        long otherNumber = Long.parseLong(other.file.substring(otherSplit + 1));
        int result = Long.compare(number, otherNumber);
        return result == 0 ? Long.compare(offset, other.offset) : result;
    }
}
