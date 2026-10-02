package cn.utcy.teaching.ai.llm;

import cn.utcy.teaching.ai.infrastructure.DashScopeProperties;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "AI_LIVE_KEY_FILE", matches = ".+")
class LlmCallsLiveTest {

    private static final DashScopeProperties PROPERTIES = new DashScopeProperties(
            URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1"),
            URI.create("https://dashscope.aliyuncs.com/api/v1"), Duration.ofSeconds(5), Duration.ofMinutes(2));

    private static String key() throws Exception {
        return Files.readString(Path.of(System.getenv("AI_LIVE_KEY_FILE"))).trim();
    }

    @Test
    void thinkingStreamAndPlainStream() throws Exception {
        LlmCalls calls = new LlmCalls(new LlmModels(PROPERTIES), PROPERTIES);
        ModelConfig thinking = new ModelConfig("t", "qwen-plus", true, 0.3, 0.9, 512);
        StringBuilder thought = new StringBuilder();
        StringBuilder text = new StringBuilder();
        ChatResponse response = calls.chat(calls.models().chatModel(key(), thinking), ChatRequest.builder()
                .messages(SystemMessage.from("你是计算器"), UserMessage.from("17*23 等于多少?只回答数字")).build(),
                new LlmCalls.Listener() {
                    @Override
                    public void onText(String delta) {
                        text.append(delta);
                    }

                    @Override
                    public void onThinking(String delta) {
                        thought.append(delta);
                    }
                });
        System.out.println("thinking chars=" + thought.length() + " text=" + text);
        assertThat(response.aiMessage().text()).contains("391");
        assertThat(thought.length()).as("思考流应到达").isGreaterThan(0);

        ModelConfig plain = new ModelConfig("p", "qwen-plus", false, 0.3, 0.9, 256);
        String answer = calls.chatText(key(), plain, List.of(SystemMessage.from("只回答数字"), UserMessage.from("2+2=?")), false, null);
        System.out.println("plain text=" + answer);
        assertThat(answer).contains("4");
        String json = calls.chatText(key(), plain, List.of(SystemMessage.from("用 JSON 回答:{\"answer\": 数字}"), UserMessage.from("3+3=?")), true, null);
        System.out.println("json text=" + json);
        assertThat(json.strip()).startsWith("{");
    }

    @Test
    void embeddingDimensionsAccepted() throws Exception {
        LlmCalls calls = new LlmCalls(new LlmModels(PROPERTIES), PROPERTIES);
        List<float[]> vectors = calls.embed(key(), "text-embedding-v4", 1024, List.of("苹果", "香蕉", "汽车"));
        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(0)).hasSize(1024);
    }

    @Test
    void toolWithDefsAccepted() throws Exception {
        LlmCalls calls = new LlmCalls(new LlmModels(PROPERTIES), PROPERTIES);
        ModelConfig plain = new ModelConfig("p", "qwen-plus", false, 0.1, 0.9, 512);
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var op = mapper.readTree("{\"type\":\"object\",\"properties\":{\"op\":{\"const\":\"add_page\"},\"title\":{\"type\":\"string\"},\"child\":{\"$ref\":\"#/definitions/Child\"}},\"required\":[\"op\",\"title\"]}");
        var child = mapper.readTree("{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\",\"minimum\":1}},\"required\":[\"n\"]}");
        ToolSpecification tool = ToolSpecification.builder().name("edit_deck").description("批量修改课件")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("intent", "意图")
                        .addProperty("ops", JsonArraySchema.builder().items(JsonReferenceSchema.builder().reference(ToolSchemas.DEFS_REF_PREFIX + "AddPageOp").build()).build())
                        .required("intent", "ops")
                        .definitions(ToolSchemas.definitions(Map.of("AddPageOp", op, "Child", child)))
                        .build())
                .build();
        AtomicInteger partials = new AtomicInteger();
        ChatResponse response = calls.chat(calls.models().chatModel(key(), plain), ChatRequest.builder()
                .messages(SystemMessage.from("你必须调用工具完成任务"), UserMessage.from("给课件加一页,标题「光合作用」,child.n 填 2"))
                .toolSpecifications(tool).build(), new LlmCalls.Listener() {
            @Override
            public void onToolCallDelta(PartialToolCall call) {
                partials.incrementAndGet();
            }
        });
        List<ToolExecutionRequest> requests = response.aiMessage().toolExecutionRequests();
        System.out.println("tool calls=" + requests + " partials=" + partials);
        assertThat(requests).isNotEmpty();
        assertThat(requests.get(0).name()).isEqualTo("edit_deck");
        assertThat(requests.get(0).arguments()).contains("add_page").contains("光合作用");
        assertThat(partials.get()).as("工具参数应分片流出").isGreaterThan(0);
    }
}
