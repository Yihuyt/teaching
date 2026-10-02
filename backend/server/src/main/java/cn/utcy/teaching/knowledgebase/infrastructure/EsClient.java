package cn.utcy.teaching.knowledgebase.infrastructure;

import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.shared.error.DomainException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Elasticsearch 手写 REST 客户端:本模块只用 6 个端点、两种固定查询形状,
 * 不引官方 elasticsearch-java(省 transport/jakarta-json 依赖且不与服务端版本耦合,
 * 与 MineruClient 的手写 HttpClient 风格一致)。
 * 错误对外统一 502「检索服务暂时不可用」,细节只进服务端日志。
 */
@Component
public class EsClient {

    private static final Logger log = LoggerFactory.getLogger(EsClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String authorization;

    public EsClient(ObjectMapper objectMapper, KnowledgebaseProperties properties) {
        this.objectMapper = objectMapper;
        String uri = properties.es().uri();
        this.baseUrl = uri.endsWith("/") ? uri.substring(0, uri.length() - 1) : uri;
        String password = properties.es().password() == null ? "" : properties.es().password();
        this.authorization = "Basic " + Base64.getEncoder().encodeToString(
                (properties.es().username() + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.es().connectTimeout())
                .build();
    }

    public void createIndex(String index, int dimension) {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode settings = payload.putObject("settings");
        settings.put("number_of_shards", 1);
        settings.put("number_of_replicas", 0);
        ObjectNode props = payload.putObject("mappings").putObject("properties");
        props.putObject("document_id").put("type", "long");
        props.putObject("seq").put("type", "integer");
        props.putObject("section").put("type", "text").put("analyzer", "cjk");
        props.putObject("content").put("type", "text").put("analyzer", "cjk");
        ObjectNode vector = props.putObject("vector");
        vector.put("type", "dense_vector");
        vector.put("dims", dimension);
        vector.put("index", true);
        vector.put("similarity", "cosine");
        ObjectNode indexOptions = vector.putObject("index_options");
        indexOptions.put("type", "hnsw");
        indexOptions.put("m", 16);
        indexOptions.put("ef_construction", 100);
        HttpResponse<String> response = send("PUT", "/" + index, payload.toString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return;
        }
        // 幂等:并发的两次首次入库都会来建同一索引,已存在即视为建好
        if (response.statusCode() == 400 && response.body() != null
                && response.body().contains("resource_already_exists_exception")) {
            return;
        }
        throw failure("PUT /" + index, response);
    }

    public void deleteIndexIfExists(String index) {
        HttpResponse<String> response = send("DELETE", "/" + index, null);
        if (response.statusCode() != 200 && response.statusCode() != 404) {
            throw failure("DELETE /" + index, response);
        }
    }

    public record ChunkDoc(long documentId, int seq, String section, String content,
                           float[] vector) {
    }

    /**
     * 批量写入(NDJSON _bulk,refresh=wait_for:文档置 ready 即可被检索,
     * 消除入库刚完成就提问的刷新窗口);任何条目失败即整体报错(不静默丢块)
     */
    public void bulkIndex(String index, List<ChunkDoc> docs) {
        StringBuilder body = new StringBuilder();
        for (ChunkDoc doc : docs) {
            ObjectNode action = objectMapper.createObjectNode();
            action.putObject("index").put("_id", doc.documentId() + "-" + doc.seq());
            body.append(action).append('\n');
            ObjectNode source = objectMapper.createObjectNode();
            source.put("document_id", doc.documentId());
            source.put("seq", doc.seq());
            source.put("section", doc.section());
            source.put("content", doc.content());
            ArrayNode vector = source.putArray("vector");
            for (float value : doc.vector()) {
                vector.add(value);
            }
            body.append(source).append('\n');
        }
        JsonNode result = request("POST", "/" + index + "/_bulk?refresh=wait_for", body.toString());
        if (result.path("errors").asBoolean(false)) {
            log.error("ES bulk 部分失败: {}", result.path("items").toString());
            throw unavailable();
        }
    }

    public void deleteByDocumentId(String index, long documentId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.putObject("query").putObject("term").put("document_id", documentId);
        HttpResponse<String> response =
                send("POST", "/" + index + "/_delete_by_query", payload.toString());
        if (response.statusCode() != 200 && response.statusCode() != 404) {
            throw failure("POST /" + index + "/_delete_by_query", response);
        }
    }

    public List<String> searchBm25(String index, String query, int size) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("size", size);
        payload.put("_source", false);
        ArrayNode should = payload.putObject("query").putObject("bool").putArray("should");
        should.addObject().putObject("match").putObject("content").put("query", query);
        should.addObject().putObject("match").putObject("section").put("query", query);
        return collectIds(request("POST", "/" + index + "/_search", payload.toString()));
    }

    public List<String> searchKnn(String index, float[] vector, int size) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("size", size);
        payload.put("_source", false);
        ObjectNode knn = payload.putObject("knn");
        knn.put("field", "vector");
        knn.put("k", size);
        knn.put("num_candidates", Math.max(100, size * 10));
        ArrayNode queryVector = knn.putArray("query_vector");
        for (float value : vector) {
            queryVector.add(value);
        }
        return collectIds(request("POST", "/" + index + "/_search", payload.toString()));
    }

    private List<String> collectIds(JsonNode result) {
        List<String> ids = new ArrayList<>();
        result.path("hits").path("hits").forEach(hit -> ids.add(hit.path("_id").asText()));
        return ids;
    }

    // ---- 传输 ---------------------------------------------------------------

    private JsonNode request(String method, String path, String body) {
        HttpResponse<String> response = send(method, path, body);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw failure(method + " " + path, response);
        }
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            log.error("ES 响应解析失败: {} {}", method, path, exception);
            throw unavailable();
        }
    }

    private HttpResponse<String> send(String method, String path, String body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json");
        builder = body == null
                ? builder.method(method, HttpRequest.BodyPublishers.noBody())
                : builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            log.error("ES 请求失败: {} {}", method, path, exception);
            throw unavailable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private DomainException failure(String what, HttpResponse<String> response) {
        log.error("ES 调用失败: {} -> HTTP {} {}", what, response.statusCode(),
                Text.abbreviate(response.body(), 500));
        return unavailable();
    }

    private static DomainException unavailable() {
        return new DomainException(HttpStatus.BAD_GATEWAY, "检索服务暂时不可用,请稍后重试");
    }
}
