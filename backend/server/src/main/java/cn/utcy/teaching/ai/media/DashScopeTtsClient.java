package cn.utcy.teaching.ai.media;

import cn.utcy.teaching.ai.llm.AiUnavailableException;

import cn.utcy.teaching.ai.infrastructure.DashScopeProperties;

import cn.utcy.teaching.shared.util.Text;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * 阿里云百炼语音合成(qwen-tts 系,多模态生成端点):响应携带音频 URL,
 * 二段下载(下载无需鉴权)。与 chat 客户端共用 HttpClient;密钥按次传入(用户级配置)。
 * 网络瞬时故障指数退避重试(有界 4 次),失败明确抛出。
 */
@Component
public class DashScopeTtsClient {

    private static final int MAX_ATTEMPTS = 4;
    private static final Logger log = LoggerFactory.getLogger(DashScopeTtsClient.class);

    public record TtsResult(byte[] audio, String format) {
    }

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DashScopeProperties properties;

    public DashScopeTtsClient(HttpClient aiHttpClient, ObjectMapper objectMapper,
                              DashScopeProperties properties) {
        this.httpClient = aiHttpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public TtsResult synthesize(String apiKey, String model, String voice, String text) {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return doSynthesize(apiKey, model, voice, text);
            } catch (IOException exception) {
                lastFailure = exception;
                log.warn("TTS 网络故障(attempt {}/{}): {}", attempt, MAX_ATTEMPTS, exception.getMessage());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AiUnavailableException("等待语音合成时线程被中断");
            }
            if (attempt < MAX_ATTEMPTS) {
                try {
                    Thread.sleep(1000L << (attempt - 1));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AiUnavailableException("等待语音合成时线程被中断");
                }
            }
        }
        throw new AiUnavailableException(
                "语音合成网络失败(已重试 " + MAX_ATTEMPTS + " 次): " + lastFailure.getMessage());
    }

    private TtsResult doSynthesize(String apiKey, String model, String voice, String text)
            throws IOException, InterruptedException {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        ObjectNode input = payload.putObject("input");
        input.put("text", text);
        input.put("voice", voice);
        input.put("language_type", "Chinese");
        payload.putObject("parameters").put("rate", 0);

        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        properties.nativeBaseUrl().toString()
                                + "/services/aigc/multimodal-generation/generation"))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            String bodyText = new String(response.body(), StandardCharsets.UTF_8);
            throw new AiUnavailableException("语音合成失败(HTTP " + response.statusCode()
                    + "): " + Text.abbreviate(bodyText, 300));
        }
        JsonNode data = objectMapper.readTree(response.body());
        String url = data.path("output").path("audio").path("url").asText(null);
        if (url == null) {
            throw new AiUnavailableException(
                    "语音合成响应中没有音频 URL: " + Text.abbreviate(data.toString(), 300));
        }
        // 预签名 URL 必须原样使用:字符串模板解析与重编码会弄坏签名
        HttpResponse<byte[]> audio = httpClient.send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (audio.statusCode() != 200 || audio.body() == null || audio.body().length == 0) {
            throw new AiUnavailableException("语音音频下载失败(HTTP " + audio.statusCode() + ")");
        }
        return new TtsResult(audio.body(), "wav");
    }

}
