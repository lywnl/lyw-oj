package com.lyw.system.questionsync;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.lyw.system.questionsync.SyncFixtures.document;
import static org.mockito.Mockito.*;

class ElasticsearchIndexStoreTest {
    private HttpServer server;
    private RestClient client;
    private ElasticsearchIndexStore store;
    private boolean exists = true;
    private String autoCreate = "-idx_question,+*";
    private String bulkResponse;
    private String searchResponse;
    private int searchStatus = 200;
    private int closeStatus = 200;
    private String closeResponse = "{\"succeeded\":true,\"num_freed\":1}";
    private volatile boolean failSecondSearch;
    private volatile boolean missingAfterClose;
    private final AtomicInteger searches = new AtomicInteger(), closes = new AtomicInteger();
    private final List<String> writes = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            requests.add(method + " " + exchange.getRequestURI());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200; String response = "{}";
            if ("HEAD".equals(method)) {
                exchange.sendResponseHeaders(exists ? 200 : 404, -1); exchange.close(); return;
            } else if (path.endsWith("/_mapping")) response = mapping();
            else if (path.equals("/_cluster/settings")) response = JSON.toJSONString(Map.of("defaults", Map.of("action.auto_create_index", autoCreate)));
            else if (path.equals("/_bulk")) { writes.add(body); response = bulkResponse; }
            else if (path.equals("/idx_question/_pit")) response = "{\"id\":\"pit-1\"}";
            else if (path.equals("/_search")) {
                writes.add(body); response = searchResponse; status = searchStatus;
                if (searches.incrementAndGet() > 1 && failSecondSearch) status = 500;
            }
            else if (path.equals("/_pit")) {
                response = closeResponse; status = closeStatus;
                if (closes.incrementAndGet() > 1 && missingAfterClose) { status = 404; response = "{\"succeeded\":true,\"num_freed\":0}"; }
            }
            else if (!path.equals("/_pit")) status = 500;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        client = RestClient.builder(new HttpHost("127.0.0.1", server.getAddress().getPort())).build();
        store = new ElasticsearchIndexStore(client, 1000);
    }
    @AfterEach void stop() throws Exception { if (client != null) client.close(); if (server != null) server.stop(0); }

    private String mapping() {
        Map<String, Object> fields = new LinkedHashMap<>();
        Map.of("questionId", "long", "title", "text", "difficulty", "byte", "timeLimit", "long", "spaceLimit", "long",
                "content", "text", "questionCase", "text", "mainFuc", "text", "defaultCode", "text", "createTime", "date")
                .forEach((name, type) -> fields.put(name, Map.of("type", type)));
        fields.put("title", Map.of("type", "text", "analyzer", "ik_max_word", "search_analyzer", "ik_smart"));
        fields.put("createTime", Map.of("type", "date", "format", "date_hour_minute_second"));
        return JSON.toJSONString(Map.of("idx_question", Map.of("mappings", Map.of("properties", fields))));
    }
    private String item(String operation, long id, int status, boolean error) {
        Map<String, Object> value = new LinkedHashMap<>(); value.put("_index", "idx_question");
        value.put("_id", Long.toString(id)); value.put("status", status);
        if (error) value.put("error", Map.of("type", status == 404 ? "index_not_found_exception" : "es_rejected_execution_exception"));
        return JSON.toJSONString(Map.of(operation, value));
    }
    @Test void fullIndexOperationIncludesExplicitNullAndExactLargeId() throws Exception {
        long id = 9007199254740993L; var base = document(id, "中文😀");
        var nulled = new QuestionDocument(id, base.title(), base.difficulty(), base.timeLimit(), base.spaceLimit(),
                base.content(), null, base.mainFuc(), base.defaultCode(), base.createTime());
        bulkResponse = "{\"items\":[" + item("index", id, 201, false) + "]}";
        store.write(List.of(IndexMutation.put(nulled)));
        String[] lines = writes.get(0).split("\n");
        assertEquals(Long.toString(id), JSON.parseObject(lines[0]).getJSONObject("index").getString("_id"));
        JSONObject source = JSON.parseObject(lines[1]);
        assertEquals("中文😀", source.getString("title")); assertTrue(source.containsKey("questionCase"));
        assertNull(source.get("questionCase")); assertEquals(10, source.size());
        assertTrue(writes.get(0).endsWith("\n")); assertFalse(requests.stream().anyMatch(value -> value.startsWith("PUT")));
    }
    @Test void partialFailureDoesNotTreatHttp200AsBatchSuccess() {
        bulkResponse = "{\"errors\":true,\"items\":[" + item("index", 1, 201, false) + "," + item("index", 2, 429, true) + "]}";
        assertThrows(IOException.class, () -> store.write(List.of(IndexMutation.put(document(1, "A")), IndexMutation.put(document(2, "B")))));
    }
    @Test void consecutiveBulksDoNotRepeatSuccessfulBoundaryValidation() throws Exception {
        store.validate();
        assertEquals(3, requests.size());
        bulkResponse = "{\"items\":[" + item("index", 1, 201, false) + "]}";
        store.write(List.of(IndexMutation.put(document(1, "A"))));
        store.write(List.of(IndexMutation.put(document(1, "B"))));
        assertEquals(List.of("POST /_bulk", "POST /_bulk"), requests.subList(3, requests.size()));
    }
    @Test void repeatedDeleteSucceedsButRemovedIndexFailsAtBulkBoundary() throws Exception {
        store.validate();
        bulkResponse = "{\"items\":[" + item("delete", 1, 404, false) + "]}";
        store.write(List.of(IndexMutation.delete(1))); assertEquals(1, writes.size());
        exists = false;
        bulkResponse = "{\"errors\":true,\"items\":[" + item("index", 1, 404, true) + "]}";
        assertThrows(SyncBlockedException.class, () -> store.write(List.of(IndexMutation.put(document(1, "A")))));
        bulkResponse = "{\"errors\":true,\"items\":[" + item("delete", 1, 404, true) + "]}";
        assertThrows(SyncBlockedException.class, () -> store.write(List.of(IndexMutation.delete(1))));
        assertEquals(3, writes.size());
        assertEquals(1, requests.stream().filter(value -> value.startsWith("HEAD ")).count());
        assertFalse(requests.stream().anyMatch(value -> value.startsWith("PUT ")));
    }
    @Test void missingIndexIsRejectedAtInitialValidation() {
        exists = false;
        assertThrows(SyncBlockedException.class, store::validate);
        assertEquals(List.of("HEAD /idx_question"), requests);
        assertTrue(writes.isEmpty());
    }
    @Test void implicitCreationPolicyMustProtectTargetWithoutChangingItsMapping() throws Exception {
        store.validate(); assertFalse(requests.stream().anyMatch(value -> value.startsWith("PUT")));
        autoCreate = "+*,-idx_question";
        assertThrows(SyncBlockedException.class, store::validate);
        assertFalse(ElasticsearchIndexStore.autoCreationAllowed("-idx_*,+*"));
        assertTrue(ElasticsearchIndexStore.autoCreationAllowed("+idx_*, -*"));
    }
    @Test void pitOutlivesCandidateWaitAndIncompleteSearchCannotBecomeDeleteCandidates() throws Exception {
        searchResponse = "{\"timed_out\":false,\"_shards\":{\"failed\":0},\"hits\":{\"hits\":[{\"_id\":\"1\",\"sort\":[1]}]}}";
        var page = store.page(null, 500); assertEquals(List.of(1L), page.ids());
        assertEquals("10m", JSON.parseObject(writes.get(0)).getJSONObject("pit").getString("keep_alive"));
        assertTrue(requests.contains("POST /idx_question/_pit?keep_alive=10m"));
        searchResponse = "{\"timed_out\":false,\"_shards\":{\"failed\":1},\"hits\":{\"hits\":[]}}";
        assertThrows(IOException.class, () -> store.page(page.after(), 500));
    }

    @Test void firstSearchAndCursorDeleteFailureRemainVisibleBeforeCursorIsReturned() {
        searchStatus = 500; searchResponse = "{\"error\":\"isolated-search-error\"}";
        closeStatus = 500;
        assertThrows(CursorCleanupException.class, () -> store.page(null, 500));
        assertTrue(requests.contains("POST /idx_question/_pit?keep_alive=10m"));
        assertTrue(requests.contains("DELETE /_pit"));
    }

    @Test void http200WithoutSuccessfulCursorDeletionIsNotCleanedUp() {
        closeResponse = "{\"succeeded\":false,\"num_freed\":0}";
        assertThrows(IOException.class, () -> store.closeCursor(List.of("pit-1")));
    }

    @Test void repeatedCloseAndExplicitlyMissingPitAreSuccessful() throws Exception {
        missingAfterClose = true;
        store.closeCursor(List.of("pit-1"));
        assertDoesNotThrow(() -> store.closeCursor(List.of("pit-1")));
        assertEquals(2, closes.get());
        closeStatus = 404; closeResponse = "{\"succeeded\":true,\"num_freed\":0}"; missingAfterClose = false;
        assertDoesNotThrow(() -> store.closeCursor(List.of("expired-pit")));
    }

    @Test void arbitrary404IsNotTreatedAsSuccessfulCleanup() {
        closeStatus = 404;
        for (String response : List.of("{}", "{\"error\":\"not found\"}", "{\"succeeded\":false,\"num_freed\":0}",
                "{\"succeeded\":true}", "{\"succeeded\":true,\"num_freed\":1}")) {
            closeResponse = response;
            assertThrows(IOException.class, () -> store.closeCursor(List.of("pit-1")));
        }
    }

    @Test void searchFailureCanCloseAgainDuringRunnerCleanupAndThenRecover() throws Exception {
        searchResponse = "{\"timed_out\":false,\"_shards\":{\"failed\":0},\"hits\":{\"hits\":[{\"_id\":\"1\",\"sort\":[1]}]}}";
        failSecondSearch = true; missingAfterClose = true;
        try (var fixture = new ReconcileTestSupport()) {
            when(fixture.reader.find(List.of(1L))).thenReturn(Map.of(1L, document(1, "A")));
            when(fixture.index.page(nullable(List.class), eq(500))).thenAnswer(call -> store.page(call.getArgument(0), 500));
            doAnswer(call -> { store.closeCursor(call.getArgument(0)); return null; }).when(fixture.index).closeCursor(anyList());
            fixture.start();
            assertEquals(500, assertThrows(ReconciliationException.class, () -> fixture.runner.requestReconciliation().await()).status());
            ReconcileTestSupport.waitFor(() -> "RUNNING".equals(fixture.runner.snapshot().get("state")));
            assertEquals(false, fixture.runner.snapshot().get("reconciliationCleanupFailed"));
            assertEquals(false, fixture.runner.snapshot().get("reconciliationActive"));
            assertTrue(closes.get() >= 2);
            failSecondSearch = false;
            searchResponse = "{\"timed_out\":false,\"_shards\":{\"failed\":0},\"hits\":{\"hits\":[]}}";
            assertEquals(0, fixture.runner.requestReconciliation().await().repaired());
        }
    }
}
