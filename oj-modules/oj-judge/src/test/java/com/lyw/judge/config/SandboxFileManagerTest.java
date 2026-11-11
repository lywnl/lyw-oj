package com.lyw.judge.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class SandboxFileManagerTest {
    @TempDir Path temporary;

    @Test
    void removesAllTaskFilesButKeepsHealthyMountRoot() throws Exception {
        SandboxFileManager files = new SandboxFileManager(temporary.resolve("pool"), 10000);
        Path directory = files.create("case-1");
        files.writeSource(directory, "// 中文\npublic class Solution {}");
        Files.writeString(directory.resolve("Solution.class"), "compiled");
        Files.writeString(directory.resolve("Solution$Nested.class"), "nested");
        Files.createDirectories(directory.resolve("generated/sub"));
        Files.writeString(directory.resolve("generated/sub/arbitrary.data"), "data");
        files.clean(directory, false);
        assertTrue(Files.isDirectory(directory));
        try (var entries = Files.list(directory)) { assertEquals(0, entries.count()); }
        files.writeSource(directory, "public class Solution {}");
        files.clean(directory, true);
        assertFalse(Files.exists(directory));
        files.clean(directory, true);
    }

    @Test
    void symlinksNeverDeleteTheirTargetsAndCannotReplaceSource() throws Exception {
        Path outside = Files.createDirectories(temporary.resolve("outside"));
        Path sentinel = Files.writeString(outside.resolve("keep.txt"), "其他任务的数据");
        SandboxFileManager files = new SandboxFileManager(temporary.resolve("pool"), 10000);
        Path directory = files.create("case-2");
        Files.createSymbolicLink(directory.resolve("linked-directory"), outside);
        Files.createSymbolicLink(directory.resolve("Solution.java"), sentinel);
        assertThrows(IOException.class, () -> files.writeSource(directory, "不能覆盖外部文件"));
        files.clean(directory, false);
        assertEquals("其他任务的数据", Files.readString(sentinel));
        assertTrue(Files.isDirectory(outside));
        assertThrows(IOException.class, () -> files.clean(outside, true));
        assertThrows(IllegalArgumentException.class, () -> files.directory("../outside"));
    }

    @Test
    void refusesExistingOrLinkedRootWithoutDeletingIt() throws Exception {
        SandboxFileManager files = new SandboxFileManager(temporary.resolve("pool"), 10000);
        Path existing = files.create("old");
        Path sentinel = Files.writeString(existing.resolve("keep"), "旧目录");
        assertThrows(IOException.class, () -> files.create("old"));
        assertEquals("旧目录", Files.readString(sentinel));
        Files.createSymbolicLink(temporary.resolve("pool/linked"), existing);
        assertThrows(IOException.class, () -> files.clean(temporary.resolve("pool/linked"), true));
        assertEquals("旧目录", Files.readString(sentinel));
    }

    @Test
    void cleanupBudgetFailureDoesNotPretendDirectoryIsEmpty() throws Exception {
        AtomicLong clock = new AtomicLong();
        SandboxFileManager files = new SandboxFileManager(temporary.resolve("pool"), 1,
                () -> clock.getAndAdd(1_000_000));
        Path directory = files.create("slow");
        Path sentinel = Files.writeString(directory.resolve("keep"), "data");
        assertThrows(IOException.class, () -> files.clean(directory, false));
        assertTrue(Files.exists(sentinel));
    }

    @Test
    void deepUserDirectoriesFailWithIOExceptionInsteadOfExhaustingHostStack() throws Exception {
        SandboxFileManager files = new SandboxFileManager(temporary.resolve("pool"), 10000);
        Path directory = files.create("deep");
        Path nested = directory;
        for (int level = 0; level < 70; level++) nested = Files.createDirectory(nested.resolve("d"));
        Files.writeString(nested.resolve("data"), "keep");
        IOException failure = assertThrows(IOException.class, () -> files.clean(directory, true));
        assertTrue(failure.getMessage().contains("深度"));
        assertTrue(Files.exists(nested.resolve("data")));
    }
}
