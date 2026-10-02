package cn.utcy.teaching.judge.sandbox;

import cn.utcy.teaching.judge.config.JudgeProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

@Component
public class GoJudgeClient {

    private static final TypeReference<List<GoJudgeResponse>> RESPONSE_TYPE = new TypeReference<>() {
    };

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;

    public GoJudgeClient(HttpClient httpClient, ObjectMapper objectMapper, JudgeProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.baseUri = properties.goJudgeUrl();
    }

    public String version() {
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/version"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        String body = send(request);
        try {
            JsonNode response = objectMapper.readTree(body);
            JsonNode version = response.get("buildVersion");
            if (version == null || !version.isTextual()) {
                throw new GoJudgeUnavailableException("go-judge /version 缺少 buildVersion");
            }
            return version.textValue();
        } catch (JsonProcessingException exception) {
            throw new GoJudgeUnavailableException("go-judge /version 返回非法 JSON", exception);
        }
    }

    public GoJudgeResponse run(GoJudgeRequest requestBody, Duration timeout) {
        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(requestBody);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化 go-judge 请求", exception);
        }
        HttpRequest request = HttpRequest.newBuilder(baseUri.resolve("/run"))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        String body = send(request);
        try {
            List<GoJudgeResponse> responses = objectMapper.readValue(body, RESPONSE_TYPE);
            if (responses.size() != 1) {
                throw new GoJudgeUnavailableException("go-judge 必须为单命令请求返回一个结果");
            }
            return responses.getFirst();
        } catch (JsonProcessingException exception) {
            throw new GoJudgeUnavailableException("go-judge /run 返回非法 JSON", exception);
        }
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new GoJudgeUnavailableException("go-judge 返回 HTTP " + response.statusCode());
            }
            return response.body();
        } catch (IOException exception) {
            throw new GoJudgeUnavailableException("无法连接 go-judge", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new GoJudgeUnavailableException("等待 go-judge 时线程被中断", exception);
        }
    }
}
