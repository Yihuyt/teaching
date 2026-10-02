package cn.utcy.teaching.ai.media;

import cn.utcy.teaching.ai.llm.AiUnavailableException;

import cn.utcy.teaching.ai.infrastructure.DashScopeProperties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 文生图(通义千问 qwen-image 系列,百炼原生同步接口 multimodal-generation):
 * 结果只给 24 小时有效的下载 URL,这里当场下载成字节交调用方落自家存储。失败一律明确抛出,不做占位图。
 */
@Component
public class QwenImageClient {

    public record GeneratedImage(byte[] bytes, String contentType, int width, int height) {
    }

    private static final int MAX_IMAGE_BYTES = 20 * 1024 * 1024;
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final DashScopeProperties properties;

    public QwenImageClient(HttpClient aiHttpClient, ObjectMapper objectMapper, DashScopeProperties properties) {
        this.httpClient = aiHttpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** size 形如 "1664*928";negativePrompt 可空 */
    public GeneratedImage generate(String apiKey, String model, String prompt, String negativePrompt, String size) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("model", model);
        ArrayNode messages = payload.putObject("input").putArray("messages");
        messages.addObject().put("role", "user").putArray("content").addObject().put("text", prompt);
        ObjectNode parameters = payload.putObject("parameters");
        parameters.put("size", size);
        parameters.put("n", 1);
        parameters.put("prompt_extend", true);
        parameters.put("watermark", false);
        if (negativePrompt != null && !negativePrompt.isBlank()) {
            parameters.put("negative_prompt", negativePrompt);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        properties.nativeBaseUrl().toString() + "/services/aigc/multimodal-generation/generation"))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException exception) {
            throw new AiUnavailableException("无法连接文生图服务", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException("等待文生图服务时线程被中断", exception);
        }
        if (response.statusCode() != 200) {
            String detail = drainError(response);
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new AiUnavailableException("大模型 API Key 无效,请到「账户设置 → AI 服务」检查配置");
            }
            throw new AiUnavailableException("文生图服务返回错误(HTTP " + response.statusCode() + "): " + detail);
        }
        JsonNode body;
        try (InputStream in = response.body()) {
            body = objectMapper.readTree(in);
        } catch (IOException exception) {
            throw new AiUnavailableException("文生图响应读取失败", exception);
        }
        String url = null;
        for (JsonNode part : body.path("output").path("choices").path(0).path("message").path("content")) {
            if (part.path("image").isTextual()) {
                url = part.get("image").asText();
                break;
            }
        }
        if (url == null) {
            JsonNode message = body.path("message");
            throw new AiUnavailableException("文生图服务没有返回图片" + (message.isTextual() ? ": " + message.asText() : ""));
        }
        byte[] bytes = download(url);
        int width = body.path("usage").path("width").asInt(0);
        int height = body.path("usage").path("height").asInt(0);
        if (width <= 0 || height <= 0) {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
                if (image == null) {
                    throw new AiUnavailableException("文生图返回的不是可解析的图片");
                }
                width = image.getWidth();
                height = image.getHeight();
            } catch (IOException exception) {
                throw new AiUnavailableException("文生图返回的图片无法解析", exception);
            }
        }
        return new GeneratedImage(bytes, "image/png", width, height);
    }

    private byte[] download(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                throw new AiUnavailableException("下载生成图片失败(HTTP " + response.statusCode() + ")");
            }
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(MAX_IMAGE_BYTES + 1);
                if (bytes.length > MAX_IMAGE_BYTES) {
                    throw new AiUnavailableException("生成图片超过 20MB 上限");
                }
                return bytes;
            }
        } catch (IOException exception) {
            throw new AiUnavailableException("下载生成图片失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiUnavailableException("下载生成图片时线程被中断", exception);
        }
    }

    private String drainError(HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            JsonNode node = objectMapper.readTree(body);
            if (node.path("error").path("message").isTextual()) {
                return node.get("error").get("message").asText();
            }
            if (node.path("message").isTextual()) {
                return node.get("message").asText();
            }
        } catch (IOException ignored) {
            // 非 JSON 错误体
        }
        return "HTTP " + response.statusCode();
    }
}
