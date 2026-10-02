package cn.utcy.teaching.ai.structured;

import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.ai.llm.FakeLlm;
import cn.utcy.teaching.ai.llm.ModelConfig;

import cn.utcy.teaching.ai.structured.StructuredGenerator.Refined;
import cn.utcy.teaching.ai.structured.StructuredGenerator.Request;
import cn.utcy.teaching.ai.structured.StructuredGenerator.Result;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCHEMA_TEXT = """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "kind": { "type": "string", "enum": ["a", "b"] },
                "name": { "type": "string" }
              },
              "required": ["kind", "name"],
              "additionalProperties": false
            }
            """;

    private static final String GOOD_JSON = "{\"kind\":\"a\",\"name\":\"齿轮\"}";

    private static final ModelConfig MODEL =
            new ModelConfig("test", "qwen-test", false, 0.3, 0.9, 1024);

    private JsonNode schemaNode;
    private JsonSchema validator;
    private FakeLlm llm;
    private StructuredGenerator generator;
    private final List<String> retryReasons = new ArrayList<>();
    private final BiConsumer<Integer, String> recordingOnRetry =
            (round, reason) -> retryReasons.add(round + "|" + reason);

    @BeforeEach
    void setUp() throws JsonProcessingException {
        schemaNode = MAPPER.readTree(SCHEMA_TEXT);
        validator = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schemaNode);
        llm = new FakeLlm();
        generator = new StructuredGenerator(llm, MAPPER);
    }

    private Request<String> request(int maxRounds,
                                    Function<JsonNode, Refined<String>> refine,
                                    BiConsumer<Integer, String> onRetry) {
        return new Request<>("test-key", MODEL, "test", schemaNode, validator, "SYS", "USR", maxRounds, refine, onRetry);
    }

    /** 最后一次调用收到的消息(生成器逐轮追加,最后一轮最全) */
    private List<ChatMessage> capturedMessages(int expectedCalls) {
        assertThat(llm.textCalls).hasSize(expectedCalls);
        return llm.textCalls.get(expectedCalls - 1);
    }

    @Test
    @DisplayName("首轮通过:透传 refine 的 warnings,schema 随请求下发")
    void firstRoundCleanSuccess() {
        llm.answerText(GOOD_JSON);

        Result<String> result = generator.generate(request(3,
                parsed -> Refined.value(parsed.get("name").asText(), List.of("提示:标题偏长")),
                null));

        assertThat(result.value()).isEqualTo("齿轮");
        assertThat(result.warnings()).containsExactly("提示:标题偏长");
        assertThat(result.rounds()).isEqualTo(1);

        assertThat(llm.jsonModes).containsExactly(true);
        List<ChatMessage> messages = capturedMessages(1);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isEqualTo(SystemMessage.from("SYS"));
        assertThat(messages.get(1)).isEqualTo(UserMessage.from("USR"));
    }

    @Test
    @DisplayName("JSON 不可解析:回喂原文与整改要求,onRetry 收到原因")
    void invalidJsonThenSuccess() {
        llm.answerText("这不是 JSON", GOOD_JSON);

        Result<String> result = generator.generate(request(3,
                parsed -> Refined.value(parsed.get("name").asText()),
                recordingOnRetry));

        assertThat(result.value()).isEqualTo("齿轮");
        assertThat(result.rounds()).isEqualTo(2);
        assertThat(retryReasons).hasSize(1);
        assertThat(retryReasons.get(0)).startsWith("1|").contains("不是可解析的 JSON");

        List<ChatMessage> messages = capturedMessages(2);
        assertThat(messages).hasSize(4);
        assertThat(messages.get(2)).isEqualTo(AiMessage.from("这不是 JSON"));
        assertThat(messages.get(3)).isEqualTo(UserMessage.from(
                "你上一次的输出不合格:\n输出不是可解析的 JSON。\n请修正后重新输出完整 JSON(只输出 JSON,不要解释)。"));
    }

    @Test
    @DisplayName("schema 校验失败:错误清单带出多余字段/缺少字段/允许值三类细节")
    void schemaViolationDetailsAreExplained() {
        llm.answerText("{\"kind\":\"z\",\"extra\":1}", GOOD_JSON);

        Result<String> result = generator.generate(request(3,
                parsed -> Refined.value(parsed.get("name").asText()),
                recordingOnRetry));

        assertThat(result.rounds()).isEqualTo(2);
        assertThat(retryReasons).hasSize(1);
        String reason = retryReasons.get(0);
        assertThat(reason).contains("JSON Schema 校验失败:");
        assertThat(reason).contains("(多余字段: \"extra\")");
        assertThat(reason).contains("(缺少字段: \"name\")");
        assertThat(reason).contains("(实际输出: \"z\")");
        assertThat(reason).contains("(允许值: a / b)");
        assertThat(reason).contains("- (根)");
    }

    @Test
    @DisplayName("语义校验失败:错误清单以 语义校验失败 开头回喂")
    void refineErrorsAreFedBack() {
        llm.answerText(GOOD_JSON, GOOD_JSON);
        AtomicInteger calls = new AtomicInteger();

        Result<String> result = generator.generate(request(3,
                parsed -> calls.incrementAndGet() == 1
                        ? Refined.errors(List.of("答案不在选项中"))
                        : Refined.value(parsed.get("name").asText()),
                recordingOnRetry));

        assertThat(result.rounds()).isEqualTo(2);
        List<ChatMessage> messages = capturedMessages(2);
        assertThat(messages.get(3)).isInstanceOf(UserMessage.class);
        assertThat(ChatAgentLoop.textOf(messages.get(3)))
                .contains("语义校验失败:")
                .contains("- 答案不在选项中");
        assertThat(retryReasons.get(0)).contains("语义校验失败:");
    }

    @Test
    @DisplayName("轮次耗尽:抛出含轮数与最后一轮原因的异常")
    void exhaustionThrowsWithLastReason() {
        llm.answerText("垃圾");

        assertThatThrownBy(() -> generator.generate(request(2,
                parsed -> Refined.value("不会到这里"),
                null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("结构化生成在 2 轮内未通过校验,最后一轮原因:输出不是可解析的 JSON。");

        assertThat(llm.textCalls).hasSize(2);
    }
}
