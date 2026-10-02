package cn.utcy.teaching.ai.llm;

import cn.utcy.teaching.ai.infrastructure.DashScopeProperties;

import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型实例工厂:密钥是用户级配置,每个 (密钥, 模型配置) 一个实例,有界缓存复用其 HTTP 连接。
 * 生成参数(温度、top_p、输出上限、思考开关)在这里定死在实例上,调用方只管消息和工具。
 * 思考开关按 ModelConfig.reasoning 显式上送:开着的模型会流出思考内容,关着的模型不产生思考。
 */
@Component
public class LlmModels {

    private static final int CACHE_SIZE = 64;
    private static final int EMBEDDING_BATCH = 10;

    private final DashScopeProperties properties;
    private final Map<String, StreamingChatModel> chatModels = lru();
    private final Map<String, EmbeddingModel> embeddingModels = lru();

    public LlmModels(DashScopeProperties properties) {
        this.properties = properties;
    }

    public StreamingChatModel chatModel(String apiKey, ModelConfig config) {
        String key = apiKey + "\n" + config;
        synchronized (chatModels) {
            return chatModels.computeIfAbsent(key, k -> buildChatModel(apiKey, config));
        }
    }

    public EmbeddingModel embeddingModel(String apiKey, String model, int dimension) {
        String key = apiKey + "\n" + model + "\n" + dimension;
        synchronized (embeddingModels) {
            return embeddingModels.computeIfAbsent(key, k -> OpenAiEmbeddingModel.builder()
                    .baseUrl(properties.baseUrl().toString())
                    .apiKey(apiKey)
                    .modelName(model)
                    .dimensions(dimension)
                    .maxSegmentsPerBatch(EMBEDDING_BATCH)
                    .maxRetries(1)
                    .httpClientBuilder(httpClient())
                    .build());
        }
    }

    private StreamingChatModel buildChatModel(String apiKey, ModelConfig config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(properties.baseUrl().toString())
                .apiKey(apiKey)
                .modelName(config.providerModel())
                .temperature(config.temperature())
                .topP(config.topP())
                .maxTokens(config.maxOutputTokens())
                .returnThinking(true)
                .customParameters(Map.of("enable_thinking", config.reasoning()))
                .httpClientBuilder(httpClient())
                .build();
    }

    /** 连接超时按配置;读超时只管响应头到达,流中的静默由 {@link LlmCalls} 的看门狗管 */
    private JdkHttpClientBuilder httpClient() {
        return new JdkHttpClientBuilder()
                .connectTimeout(properties.connectTimeout())
                .readTimeout(properties.idleTimeout());
    }

    private static <V> Map<String, V> lru() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > CACHE_SIZE;
            }
        });
    }
}
