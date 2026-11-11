package com.lyw.system.questionsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.api.reconcile.QuestionReconcileProperties;
import com.alibaba.otter.canal.protocol.Message;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 隔离调度验收入口，仅位于 test-classes；真实 HTTP/Runner，数据源为内存替身。 */
public final class QuestionReconcileHttpProbe {
    private static volatile String mode = "NORMAL";
    private static final AtomicInteger starts = new AtomicInteger(), writes = new AtomicInteger();
    private static final java.util.Set<String> writerThreads = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Long, QuestionDocument> documents = new ConcurrentHashMap<>();

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({PropertyPlaceholderAutoConfiguration.class, ServletWebServerFactoryAutoConfiguration.class,
            DispatcherServletAutoConfiguration.class, WebMvcAutoConfiguration.class,
            JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
    @Import({QuestionReconcileWebConfiguration.class, QuestionReconcileController.class})
    static class ProbeConfiguration {
        @Bean QuestionEsSyncRunner runner(QuestionReconcileProperties settings) {
            var properties = SyncFixtures.properties(); properties.setIdleMillis(10);
            SyncSource source = new SyncSource() {
                public void open() { }
                public Message get(int size) { return null; }
                public void ack(long id) { throw new AssertionError("无增量事件，不应 ACK"); }
                public void rollback(long id) { }
                public void close() { }
            };
            PositionGuard guard = new PositionGuard() {
                public BinlogPosition check() { return new BinlogPosition("mysql-bin.000001", 4); }
                public BinlogPosition capture() { return new BinlogPosition("mysql-bin.000001", 4); }
            };
            QuestionReader reader = new QuestionReader() {
                public List<QuestionDocument> page(long after, int size) throws java.sql.SQLException {
                    writerThreads.add(Thread.currentThread().getName());
                    if (after != 0) return List.of();
                    starts.incrementAndGet();
                    if ("FAIL".equals(mode)) throw new java.sql.SQLException("隔离故障注入");
                    long deadline = System.nanoTime() + 5_000_000_000L;
                    while ("HOLD".equals(mode) && System.nanoTime() < deadline) {
                        try { Thread.sleep(20); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.sql.SQLException("测试读取中断"); }
                    }
                    return List.of(SyncFixtures.document(1, "隔离调度测试"));
                }
                public Map<Long, QuestionDocument> find(Collection<Long> ids) { return Map.of(1L, SyncFixtures.document(1, "隔离调度测试")); }
                public long count() { return 1; }
            };
            IndexStore index = new IndexStore() {
                public void validate() { }
                public boolean exists() { return true; }
                public void create() { throw new AssertionError("不允许创建真实索引"); }
                public void write(List<IndexMutation> changes) {
                    writerThreads.add(Thread.currentThread().getName());
                    for (var change : changes) {
                        writes.incrementAndGet();
                        if (change.document() == null) documents.remove(change.id());
                        else documents.put(change.id(), change.document());
                    }
                }
                public Map<Long, QuestionDocument> get(Collection<Long> ids) { return Map.copyOf(documents); }
                public Page page(List<Object> after, int size) {
                    if (after == null) return new Page(List.copyOf(documents.keySet()), List.of("test-pit"));
                    closeCursor(after); return new Page(List.of(), null);
                }
                public void closeCursor(List<Object> after) { writerThreads.add(Thread.currentThread().getName()); }
            };
            return new QuestionEsSyncRunner(properties, source, new CanalEventMapper("lywoj"), index, guard,
                    new QuestionReconciler(reader, index, guard, 500), settings);
        }
    }

    public static void main(String[] args) throws Exception {
        Path folder = Path.of(System.getProperty("probe.directory")); Files.createDirectories(folder);
        // 不使用 SpringApplication，避免第三方启动监听器导入业务 bootstrap/Nacos 配置。
        try (var context = new AnnotationConfigServletWebServerApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-web", Map.of("server.port", 8081)));
            context.register(ProbeConfiguration.class); context.refresh();
            var runner = context.getBean(QuestionEsSyncRunner.class);
            ReconcileTestSupport.waitFor(() -> "RUNNING".equals(runner.snapshot().get("state")));
            ObjectMapper json = new ObjectMapper();
            long end = System.nanoTime() + 600_000_000_000L;
            while (!Files.exists(folder.resolve("stop")) && System.nanoTime() < end) {
                try {
                    String next = Files.readString(folder.resolve("mode.txt")).trim();
                    if (List.of("NORMAL", "HOLD", "FAIL").contains(next)) mode = next;
                } catch (java.nio.file.NoSuchFileException replacing) {
                    // 宿主机原子替换绑定文件时，Docker 文件视图可能短暂看不到名字；保留上一模式。
                }
                var status = new java.util.LinkedHashMap<String, Object>(runner.snapshot());
                status.put("mode", mode); status.put("starts", starts.get()); status.put("writes", writes.get());
                status.put("writerThreads", List.copyOf(writerThreads));
                Path temporary = folder.resolve("system-status.new");
                Files.writeString(temporary, json.writeValueAsString(status));
                Files.move(temporary, folder.resolve("system-status.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                Thread.sleep(50);
            }
        }
    }
}
