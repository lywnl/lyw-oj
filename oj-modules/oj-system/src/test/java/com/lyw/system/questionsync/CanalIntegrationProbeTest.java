package com.lyw.system.questionsync;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import com.alibaba.otter.canal.protocol.Message;
import com.mysql.cj.jdbc.MysqlDataSource;
import com.lyw.common.security.exception.ServiceException;
import com.lyw.system.domain.question.dto.QuestionAddDTO;
import com.lyw.system.domain.question.dto.QuestionEditDTO;
import com.lyw.system.service.question.impl.QuestionServiceImpl;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 仅供隔离集成脚本使用的文件控制探针；不打入应用包，不提供远程接口。 */
@EnabledIfSystemProperty(named = "canal.integration", matches = "true")
class CanalIntegrationProbeTest {
    private Path directory;
    private RestClient client;
    private SyncProperties properties;
    private MysqlDataSource dataSource;
    private IndexStore index;
    private PositionGuard positions;
    private QuestionReconciler reconciler;
    private QuestionEsSyncRunner runner;
    private final AtomicReference<String> fault = new AtomicReference<>("");
    private final AtomicInteger injected = new AtomicInteger();
    private final AtomicInteger reads = new AtomicInteger();
    private final AtomicInteger activeWrites = new AtomicInteger();
    private final AtomicInteger maxWrites = new AtomicInteger();
    private final AtomicBoolean businessBatch = new AtomicBoolean();
    private final AtomicLong currentBatch = new AtomicLong(-1);
    private final AtomicLong timeoutBatch = new AtomicLong(-1);
    private final AtomicLong timeoutPreviousAck = new AtomicLong(-1);
    private final AtomicLong timeoutElapsed = new AtomicLong();
    private final AtomicInteger requestTimeouts = new AtomicInteger();
    private final MockEnvironment businessEnvironment = new MockEnvironment();
    private final QuestionMaintenanceGate businessGate = new QuestionMaintenanceGate(businessEnvironment);

    @Test void serveIsolatedIntegrationCommands() throws Exception {
        directory = Path.of(System.getProperty("canal.integration.directory"));
        write("ready.json", Map.of("ready", true));
        long last = -1;
        long deadline = System.nanoTime() + 3_600_000_000_000L;
        try {
            while (System.nanoTime() < deadline) {
                Path request = directory.resolve("request.json");
                if (!Files.exists(request)) { Thread.sleep(20); continue; }
                JSONObject input;
                try { input = JSON.parseObject(Files.readString(request)); }
                catch (java.nio.file.NoSuchFileException replaced) { Thread.sleep(20); continue; }
                long sequence = input.getLongValue("sequence");
                if (sequence == last) { Thread.sleep(20); continue; }
                last = sequence;
                Map<String, Object> response = new LinkedHashMap<>(); response.put("sequence", sequence);
                String action = input.getString("action");
                try {
                    switch (action) {
                        case "start" -> { stop(); configure(input); runner.start(); }
                        case "stop" -> stop();
                        case "fault" -> fault.set(input.getString("mode"));
                        case "gate" -> response.putAll(checkBusinessGate(input.getBooleanValue("enabled")));
                        case "maintain" -> {
                            stop(); configure(input); properties.setEnabled(false); properties.setMode(input.getString("mode"));
                            properties.setMaintenanceConfirmed(input.getBooleanValue("confirmed"));
                            int code = new QuestionSyncMaintenanceRunner(properties, positions, index, reconciler,
                                    new GenericApplicationContext()).execute();
                            response.put("exitCode", code); response.put("differences", reconciler.differences());
                            response.put("repaired", reconciler.repaired());
                        }
                        case "status", "quit" -> { }
                        default -> throw new IllegalArgumentException("未知探针操作");
                    }
                    response.put("ok", true);
                } catch (Exception failure) {
                    response.put("ok", false); response.put("error", failure.getClass().getSimpleName());
                    if (failure instanceof SyncBlockedException) response.put("reason", failure.getMessage());
                }
                if (runner != null && !"maintain".equals(action)) response.putAll(runner.snapshot());
                response.put("questionReads", reads.get()); response.put("faultsInjected", injected.get());
                response.put("maxConcurrentWrites", maxWrites.get());
                response.put("gateEnabled", businessGate.enabled());
                response.put("requestTimeouts", requestTimeouts.get()); response.put("timeoutBatch", timeoutBatch.get());
                response.put("timeoutPreviousAck", timeoutPreviousAck.get()); response.put("timeoutElapsedMillis", timeoutElapsed.get());
                write("response.json", response);
                if ("quit".equals(action)) return;
            }
            throw new IllegalStateException("集成控制器超时未结束");
        } finally { stop(); }
    }

    private Map<String, Object> checkBusinessGate(boolean enabled) {
        businessEnvironment.setProperty("question.maintenance.enabled", Boolean.toString(enabled));
        if (!enabled) return Map.of("acceptsNewWrite", businessGate.write(() -> true));
        QuestionServiceImpl service = new QuestionServiceImpl();
        ReflectionTestUtils.setField(service, "maintenanceGate", businessGate);
        int rejected = 0;
        for (Runnable action : new Runnable[]{() -> service.add(new QuestionAddDTO()),
                () -> service.edit(new QuestionEditDTO()), () -> service.delete(400L)}) {
            try {
                action.run();
                throw new IllegalStateException("题目维护门禁未拒绝新请求");
            } catch (ServiceException blocked) {
                if (blocked.getResultCode().getCode() != 3503) throw blocked;
                rejected++;
            }
        }
        return Map.of("blockedWrites", rejected, "resultCode", 3503);
    }

    private void configure(JSONObject input) throws Exception {
        Properties config = new Properties();
        try (var stream = Files.newInputStream(directory.resolve("connection.properties"))) { config.load(stream); }
        properties = new SyncProperties(); properties.setEnabled(true); properties.setDatabase(config.getProperty("database"));
        properties.setHost(config.getProperty("canal.host")); properties.setStateDirectory(config.getProperty("state.directory"));
        properties.setInitialFile(config.getProperty("initial.file")); properties.setInitialPosition(Long.parseLong(config.getProperty("initial.position")));
        properties.setSourceJdbcUrl(config.getProperty("jdbc.url")); properties.setSourceUsername("canal_sync");
        properties.setSourcePassword(config.getProperty("source.password")); properties.setPositionCheckMillis(1000);
        properties.setReconcileIntervalMillis(input.getLongValue("reconcileMillis"));
        properties.setPageSize(input.containsKey("pageSize") ? input.getIntValue("pageSize") : 500);
        dataSource = new MysqlDataSource(); dataSource.setUrl(config.getProperty("jdbc.url"));
        dataSource.setUser("app_sync"); dataSource.setPassword(config.getProperty("app.password"));
        dataSource.setConnectTimeout(5000); dataSource.setSocketTimeout(5000);
        client = RestClient.builder(HttpHost.create(config.getProperty("es.url"))).build();
        IndexStore actual = new ElasticsearchIndexStore(client, properties.getTimeoutMillis());
        index = new IndexStore() {
            public void validate() throws IOException { actual.validate(); }
            public boolean exists() throws IOException { return actual.exists(); }
            public void create() throws IOException { actual.create(); }
            public void write(List<IndexMutation> changes) throws IOException {
                businessBatch.set(!changes.isEmpty());
                if (changes.isEmpty()) return;
                int concurrent = activeWrites.incrementAndGet(); maxWrites.accumulateAndGet(concurrent, Math::max);
                try {
                    if (takeFault("response-timeout")) { writeWithDelayedResponse(changes); return; }
                    if (takeFault("partial-write")) {
                        actual.write(changes.subList(0, 1)); injected.incrementAndGet();
                        throw new IOException("测试注入：真实 ES 已成功写入首项，其余项失败");
                    }
                    actual.write(changes);
                } finally { activeWrites.decrementAndGet(); }
            }
            public Map<Long, QuestionDocument> get(Collection<Long> ids) throws IOException { return actual.get(ids); }
            public Page page(List<Object> after, int size) throws IOException { return actual.page(after, size); }
            public void closeCursor(List<Object> after) throws IOException { actual.closeCursor(after); }
        };
        QuestionReader actualReader = new JdbcQuestionReader(dataSource, 5000);
        QuestionReader counted = new QuestionReader() {
            public List<QuestionDocument> page(long after, int size) throws SQLException { reads.incrementAndGet(); return actualReader.page(after, size); }
            public Map<Long, QuestionDocument> find(Collection<Long> ids) throws SQLException { reads.incrementAndGet(); return actualReader.find(ids); }
            public long count() throws SQLException { reads.incrementAndGet(); return actualReader.count(); }
        };
        positions = new SourcePositionGuard(properties, dataSource);
        reconciler = new QuestionReconciler(counted, index, positions, properties.getPageSize());
        SyncSource actualSource = new TcpCanalSource(properties);
        SyncSource source = new SyncSource() {
            public void open() { actualSource.open(); }
            public Message get(int size) {
                Message message = actualSource.get(size);
                if (message != null) currentBatch.set(message.getId());
                return message;
            }
            public void ack(long id) {
                if (businessBatch.get() && takeFault("before-ack")) { injected.incrementAndGet(); throw new IllegalStateException("测试注入：ACK 前断线"); }
                if (businessBatch.get() && takeFault("pause-before-ack")) {
                    injected.incrementAndGet();
                    try {
                        write("before-ack.json", Map.of("batchId", id));
                        while (true) Thread.sleep(1000);
                    } catch (IOException | InterruptedException interrupted) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException("测试进程在 ACK 前停止");
                    }
                }
                actualSource.ack(id);
                if (businessBatch.get() && takeFault("after-ack")) { injected.incrementAndGet(); throw new IllegalStateException("测试注入：ACK 状态不确定"); }
            }
            public void rollback(long id) { actualSource.rollback(id); }
            public void close() { actualSource.close(); }
        };
        runner = new QuestionEsSyncRunner(properties, source, new CanalEventMapper(properties.getDatabase()), index, positions, reconciler);
        reads.set(0); fault.set(""); injected.set(0); activeWrites.set(0); maxWrites.set(0); businessBatch.set(false);
        requestTimeouts.set(0); timeoutBatch.set(-1); timeoutPreviousAck.set(-1); timeoutElapsed.set(0);
    }

    private void stop() throws Exception {
        if (runner != null) { runner.stop(); assertFalse(runner.isRunning(), "旧消费者必须完全退出"); }
        if (client != null) { client.close(); client = null; }
    }
    private boolean takeFault(String expected) {
        String current = fault.get();
        return expected.equals(current) && fault.compareAndSet(current, "");
    }
    private void writeWithDelayedResponse(List<IndexMutation> changes) throws IOException {
        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool(task -> { Thread thread = new Thread(task, "test-es-response-delay"); thread.setDaemon(true); return thread; });
        proxy.setExecutor(executor);
        proxy.createContext("/", exchange -> {
            try {
                Request forward = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath());
                String query = exchange.getRequestURI().getRawQuery();
                if (query != null) for (String item : query.split("&")) {
                    String[] pair = item.split("=", 2); forward.addParameter(pair[0], pair.length == 2 ? pair[1] : "");
                }
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (body.length > 0) forward.setJsonEntity(new String(body, StandardCharsets.UTF_8));
                var response = client.performRequest(forward);
                byte[] bytes = response.getEntity() == null ? new byte[0] : EntityUtils.toByteArray(response.getEntity());
                if ("/_bulk".equals(exchange.getRequestURI().getPath())) {
                    // 真实 ES 已处理请求，再延迟响应；不能用普通 IOException 代替读超时。
                    timeoutBatch.set(currentBatch.get()); injected.incrementAndGet();
                    write("timeout-backend-applied.json", Map.of("batchId", timeoutBatch.get(), "status", response.getStatusLine().getStatusCode()));
                    Thread.sleep(properties.getTimeoutMillis() + 1000L);
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(response.getStatusLine().getStatusCode(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (IOException ignored) { /* 超时后测试客户端已关闭连接。 */ }
            finally { exchange.close(); }
        });
        proxy.start(); long started = System.nanoTime();
        try (RestClient delayed = RestClient.builder(new HttpHost("127.0.0.1", proxy.getAddress().getPort())).build()) {
            new ElasticsearchIndexStore(delayed, properties.getTimeoutMillis()).write(changes);
            throw new AssertionError("预期发生真实 ES 响应读取超时");
        } catch (IOException failure) {
            Throwable cause = failure;
            while (cause != null && !(cause instanceof SocketTimeoutException)) cause = cause.getCause();
            if (cause != null) {
                timeoutPreviousAck.set((Long) runner.snapshot().get("lastAck"));
                timeoutElapsed.set((System.nanoTime() - started) / 1000000);
                requestTimeouts.incrementAndGet();
            }
            throw failure;
        } finally { proxy.stop(0); executor.shutdownNow(); }
    }
    private void write(String name, Object value) throws IOException {
        Path temp = directory.resolve(name + ".new");
        Files.writeString(temp, JSON.toJSONString(value, JSONWriter.Feature.WriteNulls));
        Files.move(temp, directory.resolve(name), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
}
