package com.lyw.system.questionsync;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 使用同一个既有索引，不创建别名、不重写已存在的映射。 */
public final class ElasticsearchIndexStore implements IndexStore {
    private final RestClient client;
    private final int timeout;
    private static final Map<String, String> TYPES = Map.of("questionId", "long", "title", "text", "difficulty", "byte",
            "timeLimit", "long", "spaceLimit", "long", "content", "text", "questionCase", "text", "mainFuc", "text",
            "defaultCode", "text", "createTime", "date");

    public ElasticsearchIndexStore(RestClient client, int timeout) { this.client = client; this.timeout = timeout; }

    private JSONObject request(String method, String path, Object body) throws IOException {
        int query = path.indexOf('?');
        Request request = new Request(method, query < 0 ? path : path.substring(0, query));
        if (query >= 0) for (String parameter : path.substring(query + 1).split("&")) {
            String[] pair = parameter.split("=", 2);
            request.addParameter(pair[0], pair.length == 2 ? pair[1] : "");
        }
        RequestOptions.Builder options = RequestOptions.DEFAULT.toBuilder();
        options.setRequestConfig(RequestConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout)
                .setConnectionRequestTimeout(timeout).build());
        request.setOptions(options);
        if (body != null) request.setJsonEntity(body instanceof String text ? text : JSON.toJSONString(body, JSONWriter.Feature.WriteNulls));
        try {
            var response = client.performRequest(request);
            // RestClient 对 HEAD 404 默认不抛异常，必须检查状态，不能误判索引存在。
            if (response.getStatusLine().getStatusCode() >= 400) throw new ResponseException(response);
            if (response.getEntity() == null) return new JSONObject();
            return JSON.parseObject(EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8));
        } catch (ResponseException failure) {
            int status = failure.getResponse().getStatusLine().getStatusCode();
            if (status == 400 || status == 401 || status == 403)
                throw new SyncBlockedException("ES 请求被拒绝，HTTP " + status + "，请核对权限/映射");
            throw failure;
        } catch (com.alibaba.fastjson2.JSONException invalid) {
            throw new IOException("ES 返回无法解析的响应");
        }
    }

    @Override public boolean exists() throws IOException {
        try { request("HEAD", "/" + SyncProperties.INDEX, null); return true; }
        catch (ResponseException failure) {
            if (failure.getResponse().getStatusLine().getStatusCode() == 404) return false;
            throw failure;
        }
    }

    @Override public void validate() throws IOException {
        if (!exists()) throw new SyncBlockedException("idx_question 不存在，需显式维护初始化");
        JSONObject root = request("GET", "/" + SyncProperties.INDEX + "/_mapping", null);
        JSONObject index = root.getJSONObject(SyncProperties.INDEX);
        if (index == null) throw new SyncBlockedException("idx_question 必须是唯一的题目索引");
        JSONObject mappings = index.getJSONObject("mappings");
        JSONObject fields = mappings == null ? null : mappings.getJSONObject("properties");
        for (var expected : TYPES.entrySet()) {
            JSONObject field = fields == null ? null : fields.getJSONObject(expected.getKey());
            if (field == null || !expected.getValue().equals(field.getString("type")))
                throw new SyncBlockedException("ES 字段映射不兼容：" + expected.getKey());
        }
        String format = fields.getJSONObject("createTime").getString("format");
        if (format != null && !(format.contains("date_hour_minute_second") || format.contains("date_optional_time")
                || format.contains("yyyy-MM-dd'T'HH:mm:ss")))
            throw new SyncBlockedException("createTime 映射不能接受既有本地时间格式");
        JSONObject settings = request("GET", "/_cluster/settings?include_defaults=true&flat_settings=true", null);
        String value = null;
        for (String scope : List.of("transient", "persistent", "defaults")) {
            JSONObject section = settings.getJSONObject(scope);
            if (section != null && section.containsKey("action.auto_create_index")) {
                value = section.getString("action.auto_create_index"); break;
            }
        }
        if (autoCreationAllowed(value))
            throw new SyncBlockedException("ES 必须禁止 idx_question 隐式创建：action.auto_create_index=-idx_question,+*");
    }

    static boolean autoCreationAllowed(String setting) {
        if (setting == null || "true".equalsIgnoreCase(setting)) return true;
        if ("false".equalsIgnoreCase(setting)) return false;
        for (String item : setting.split(",")) {
            String rule = item.trim();
            boolean allow = !rule.startsWith("-");
            String pattern = rule.startsWith("+") || rule.startsWith("-") ? rule.substring(1) : rule;
            String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q");
            if (SyncProperties.INDEX.matches(regex)) return allow;
        }
        return false;
    }

    @Override public void create() throws IOException {
        if (exists()) return;
        Map<String, Object> properties = new LinkedHashMap<>();
        TYPES.forEach((name, type) -> {
            Map<String, Object> definition = new LinkedHashMap<>();
            definition.put("type", type);
            if ("title".equals(name) || "content".equals(name)) {
                definition.put("analyzer", "ik_max_word"); definition.put("search_analyzer", "ik_max_word");
            }
            if ("createTime".equals(name)) definition.put("format", "date_hour_minute_second");
            properties.put(name, definition);
        });
        request("PUT", "/" + SyncProperties.INDEX, Map.of("mappings", Map.of("properties", properties)));
        validate();
    }

    @Override public void write(List<IndexMutation> changes) throws IOException {
        if (changes.isEmpty()) return;
        StringBuilder body = new StringBuilder();
        for (var change : changes) {
            body.append(JSON.toJSONString(Map.of(change.deletion() ? "delete" : "index",
                    Map.of("_index", SyncProperties.INDEX, "_id", Long.toString(change.id()))))).append('\n');
            if (!change.deletion()) body.append(JSON.toJSONString(change.document().fields(), JSONWriter.Feature.WriteNulls)).append('\n');
        }
        JSONObject response = request("POST", "/_bulk", body.toString());
        JSONArray items = response.getJSONArray("items");
        if (items == null || items.size() != changes.size()) throw new IOException("ES 批量响应不完整");
        Integer blockedStatus = null;
        Integer retryStatus = null;
        for (int i = 0; i < items.size(); i++) {
            IndexMutation change = changes.get(i);
            JSONObject item = items.getJSONObject(i).getJSONObject(change.deletion() ? "delete" : "index");
            if (item == null || !Long.toString(change.id()).equals(item.getString("_id"))
                    || !SyncProperties.INDEX.equals(item.getString("_index")))
                throw new IOException("ES 批量响应身份不匹配");
            int status = item.getIntValue("status");
            if (item.get("error") == null && ((status >= 200 && status < 300) || (change.deletion() && status == 404))) continue;
            if (status == 400 || status == 401 || status == 403 || status == 404)
                blockedStatus = status;
            else retryStatus = status;
        }
        if (blockedStatus != null) throw new SyncBlockedException("ES 文档操作失败，HTTP " + blockedStatus + "，需核对索引/映射/权限");
        if (retryStatus != null) throw new IOException("ES 文档操作暂时失败，HTTP " + retryStatus);
    }

    @Override public Map<Long, QuestionDocument> get(Collection<Long> ids) throws IOException {
        if (ids.isEmpty()) return Map.of();
        var response = request("POST", "/" + SyncProperties.INDEX + "/_mget", Map.of("ids", ids.stream().map(String::valueOf).toList()));
        JSONArray docs = response.getJSONArray("docs");
        if (docs == null || docs.size() != ids.size()) throw new IOException("ES 核对响应不完整");
        Map<Long, QuestionDocument> result = new HashMap<>();
        for (int i = 0; i < docs.size(); i++) {
            JSONObject doc = docs.getJSONObject(i);
            if (doc.containsKey("error")) throw new IOException("ES 单文档核对失败");
            if (!doc.getBooleanValue("found")) continue;
            long id = doc.getLongValue("_id");
            if (!ids.contains(id) || result.containsKey(id)) throw new IOException("ES 核对身份不匹配");
            try {
                QuestionDocument parsed = QuestionDocument.fromIndex(doc.getJSONObject("_source"));
                result.put(id, parsed.questionId() == id ? parsed : null);
            } catch (SyncBlockedException malformed) { result.put(id, null); }
        }
        return result;
    }

    @Override public Page page(List<Object> after, int size) throws IOException {
        String pit = after == null || after.isEmpty()
                ? request("POST", "/" + SyncProperties.INDEX + "/_pit?keep_alive=10m", null).getString("id")
                : (String) after.get(0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", size); body.put("_source", false);
        body.put("pit", Map.of("id", pit, "keep_alive", "10m"));
        body.put("sort", List.of("_shard_doc"));
        if (after != null && after.size() > 1) body.put("search_after", after.subList(1, after.size()));
        try {
            JSONObject response = request("POST", "/_search", body);
            if (response.getBooleanValue("timed_out") || response.getJSONObject("_shards").getIntValue("failed") != 0)
                throw new IOException("ES 核对分页未完整完成");
            JSONArray hits = response.getJSONObject("hits").getJSONArray("hits");
            List<Long> ids = new ArrayList<>();
            List<Object> next = null;
            for (int i = 0; i < hits.size(); i++) {
                JSONObject hit = hits.getJSONObject(i);
                try { ids.add(Long.parseLong(hit.getString("_id"))); }
                catch (NumberFormatException malformed) { throw new SyncBlockedException("索引包含非题目 ID，需人工核对"); }
                next = new ArrayList<>();
                next.add(response.getString("pit_id") == null ? pit : response.getString("pit_id"));
                next.addAll(hit.getJSONArray("sort"));
            }
            if (hits.isEmpty()) closeCursor(List.of(pit));
            return new Page(ids, next);
        } catch (IOException | RuntimeException failure) {
            try { closeCursor(List.of(pit)); } catch (Exception ignored) { /* 原始异常优先，PIT 另有过期时间。 */ }
            throw failure;
        }
    }

    @Override public void closeCursor(List<Object> cursor) throws IOException {
        if (cursor != null && !cursor.isEmpty()) request("DELETE", "/_pit", Map.of("id", cursor.get(0)));
    }
}
