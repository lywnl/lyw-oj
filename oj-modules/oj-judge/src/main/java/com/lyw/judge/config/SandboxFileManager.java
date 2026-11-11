package com.lyw.judge.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.function.LongSupplier;

/** 只操作本池根目录的直接子目录，递归删除使用目录句柄且不跟随链接。 */
public class SandboxFileManager {
    private static final int MAX_DIRECTORY_DEPTH = 64;
    private final Path base;
    private final long cleanupNanos;
    private final LongSupplier clock;

    public SandboxFileManager(Path base, long cleanupTimeoutMs) {
        this(base, cleanupTimeoutMs, System::nanoTime);
    }

    SandboxFileManager(Path base, long cleanupTimeoutMs, LongSupplier clock) {
        this.base = base.toAbsolutePath().normalize();
        this.cleanupNanos = SandboxLimits.checkedNanos(cleanupTimeoutMs);
        this.clock = clock;
    }

    public Path directory(String name) {
        if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")) {
            throw new IllegalArgumentException("容器目录名称无效");
        }
        Path directory = base.resolve(name).normalize();
        if (!base.equals(directory.getParent())) throw new IllegalArgumentException("容器目录越界");
        return directory;
    }

    public Path create(String name) throws IOException {
        Files.createDirectories(base);
        requireDirectory(base);
        Path directory = directory(name);
        // 必须独占创建；既有目录不属于本次资源，不能接管或清空。
        Files.createDirectory(directory);
        return directory;
    }

    public void writeSource(Path directory, String source) throws IOException {
        validate(directory);
        Files.writeString(directory.resolve("Solution.java"), source, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    public void clean(Path directory, boolean removeRoot) throws IOException {
        long started = clock.getAsLong();
        requireDirectory(base);
        checkLocation(directory);
        try {
            requireDirectory(directory);
        } catch (NoSuchFileException missing) {
            if (removeRoot) return;
            throw missing;
        }
        checkBudget(started);
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            if (!(entries instanceof SecureDirectoryStream<Path> secure)) {
                throw new IOException("文件系统不支持安全目录清理，拒绝复用容器");
            }
            deleteEntries(secure, started, 0);
        }
        checkBudget(started);
        if (removeRoot) {
            Files.delete(directory);
        } else {
            // 清理后再次确认根目录仍存在且为空，绑定挂载的根目录不能被删除。
            validate(directory);
            try (DirectoryStream<Path> remaining = Files.newDirectoryStream(directory)) {
                if (remaining.iterator().hasNext()) throw new IOException("任务目录清理后仍有文件");
            }
        }
        checkBudget(started);
    }

    private void deleteEntries(SecureDirectoryStream<Path> directory, long started, int depth) throws IOException {
        for (Path entry : directory) {
            checkBudget(started);
            Path name = entry.getFileName();
            try {
                BasicFileAttributes attributes = directory
                        .getFileAttributeView(name, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS)
                        .readAttributes();
                if (attributes.isDirectory()) {
                    if (depth >= MAX_DIRECTORY_DEPTH) {
                        throw new IOException("任务目录超过安全清理深度 " + MAX_DIRECTORY_DEPTH);
                    }
                    try (SecureDirectoryStream<Path> child = directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS)) {
                        deleteEntries(child, started, depth + 1);
                    }
                    checkBudget(started);
                    directory.deleteDirectory(name);
                } else {
                    directory.deleteFile(name);
                }
            } catch (NoSuchFileException ignored) {
                // 已消失的目录项无需再次删除，不跟随其替代链接。
            }
            checkBudget(started);
        }
    }

    private void validate(Path directory) throws IOException {
        requireDirectory(base);
        checkLocation(directory);
        requireDirectory(directory);
    }

    private void checkLocation(Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.equals(directory) || !base.equals(normalized.getParent())) {
            throw new IOException("拒绝清理容器根目录以外的路径");
        }
    }

    private static void requireDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink()) {
            throw new IOException("沙箱目录不是安全的普通目录: " + directory);
        }
    }

    private void checkBudget(long started) throws IOException {
        if (clock.getAsLong() - started >= cleanupNanos) throw new IOException("任务文件清理超时");
        if (Thread.currentThread().isInterrupted()) throw new IOException("任务文件清理被中断");
    }
}
