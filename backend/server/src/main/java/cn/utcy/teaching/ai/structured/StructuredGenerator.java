package cn.utcy.teaching.ai.structured;

import cn.utcy.teaching.ai.llm.LlmCalls;
import cn.utcy.teaching.ai.llm.ModelConfig;

import cn.utcy.teaching.shared.util.Text;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 结构化生成:调用 → 宽容解析 → schema 校验 → 语义校验 → 不合格把错误清单
 * 回喂模型重试(校验反馈闭环),默认最多 3 轮。
 */
@Component
public class StructuredGenerator {

    private static final Logger log = LoggerFactory.getLogger(StructuredGenerator.class);

    private static final int DEFAULT_MAX_ROUNDS = 3;
    private static final int MAX_ERROR_LINES = 10;
    private static final int MAX_FEEDBACK_RAW_CHARS = 8000;
    private static final int MAX_LOGGED_RAW_CHARS = 4000;

    private final LlmCalls llm;
    private final ObjectMapper objectMapper;

    public StructuredGenerator(LlmCalls llm, ObjectMapper objectMapper) {
        this.llm = llm;
        this.objectMapper = objectMapper;
    }

    /**
     * @param model     本次调用的模型与生成参数(各业务模块自持配置)
     * @param schemaKey 仅用于日志/排错定位
     * @param maxRounds 不大于 0 时取默认 3
     * @param refine    schema 通过后的语义校验/清洗钩子:errors 表示不合格(清单回喂模型),
     *                  否则接受(可携带 warnings)
     * @param onRetry   每轮重试时的通知(SSE 上报用),可为 null
     */
    public record Request<T>(
            String apiKey,
            ModelConfig model,
            String schemaKey,
            JsonNode schema,
            JsonSchema validator,
            String system,
            String user,
            int maxRounds,
            Function<JsonNode, Refined<T>> refine,
            BiConsumer<Integer, String> onRetry,
            /* 视觉输入(可空):随首条用户消息一起发给视觉模型 */
            List<ImageContent> images) {

        public Request(String apiKey, ModelConfig model, String schemaKey, JsonNode schema, JsonSchema validator,
                       String system, String user, int maxRounds, Function<JsonNode, Refined<T>> refine,
                       BiConsumer<Integer, String> onRetry) {
            this(apiKey, model, schemaKey, schema, validator, system, user, maxRounds, refine, onRetry, null);
        }
    }

    /** 语义校验结果:errors 非空即不合格;否则取 value + warnings */
    public record Refined<T>(T value, List<String> warnings, List<String> errors) {

        public static <T> Refined<T> value(T value) {
            return new Refined<>(value, List.of(), null);
        }

        public static <T> Refined<T> value(T value, List<String> warnings) {
            return new Refined<>(value, warnings, null);
        }

        public static <T> Refined<T> errors(List<String> errors) {
            return new Refined<>(null, null, errors);
        }

        public boolean rejected() {
            return errors != null;
        }
    }

    public record Result<T>(T value, List<String> warnings, int rounds) {
    }

    public <T> Result<T> generate(Request<T> req) {
        return generate(req, null);
    }

    /**
     * 流式变体:onRawDelta 非空时原始增量带轮次回调
     * (round, rawDelta)——重试换轮时调用方据轮次重置增量解析状态。
     * 校验/refine/反馈重试与缓冲路径完全一致(仍以完整文本为准)。
     */
    public <T> Result<T> generate(Request<T> req, BiConsumer<Integer, String> onRawDelta) {
        int maxRounds = req.maxRounds() > 0 ? req.maxRounds() : DEFAULT_MAX_ROUNDS;
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(req.system()));
        if (req.images() == null || req.images().isEmpty()) {
            messages.add(UserMessage.from(req.user()));
        } else {
            List<Content> contents = new ArrayList<>();
            contents.add(TextContent.from(req.user()));
            contents.addAll(req.images());
            messages.add(UserMessage.from(contents));
        }
        boolean jsonMode = req.schema() != null;

        String lastFailure = "";
        String lastRaw = "";

        for (int round = 1; round <= maxRounds; round++) {
            final int currentRound = round;
            String raw = llm.chatText(req.apiKey(), req.model(), messages, jsonMode,
                    onRawDelta == null ? null : delta -> onRawDelta.accept(currentRound, delta));

            String reason = null;
            Refined<T> refined = null;

            JsonNode parsed = JsonResponseParser.parse(raw);
            if (parsed == null) {
                reason = "输出不是可解析的 JSON。";
            } else {
                Set<ValidationMessage> violations = req.validator().validate(parsed);
                if (!violations.isEmpty()) {
                    reason = "JSON Schema 校验失败:\n"
                            + String.join("\n", SchemaViolations.lines(violations, MAX_ERROR_LINES, objectMapper));
                } else {
                    refined = req.refine().apply(parsed);
                    if (refined.rejected()) {
                        reason = "语义校验失败:\n" + refined.errors().stream()
                                .limit(MAX_ERROR_LINES)
                                .map(e -> "- " + e)
                                .collect(Collectors.joining("\n"));
                    }
                }
            }

            if (reason == null) {
                return new Result<>(refined.value(),
                        refined.warnings() == null ? List.of() : refined.warnings(),
                        round);
            }

            lastFailure = reason;
            lastRaw = raw;
            messages.add(AiMessage.from(Text.truncate(raw, MAX_FEEDBACK_RAW_CHARS)));
            messages.add(UserMessage.from(
                    "你上一次的输出不合格:\n" + reason + "\n请修正后重新输出完整 JSON(只输出 JSON,不要解释)。"));
            if (req.onRetry() != null) {
                req.onRetry().accept(round, reason);
            }
        }

        // 原始输出只进服务端日志:异常信息面向用户,不适合塞整段模型输出
        log.warn("结构化生成 {} 在 {} 轮内未通过校验,最后一轮原因:{}\n最后一轮原始输出(截断):{}",
                req.schemaKey(), maxRounds, lastFailure,
                Text.abbreviate(lastRaw, MAX_LOGGED_RAW_CHARS));
        throw new IllegalStateException(
                "结构化生成在 " + maxRounds + " 轮内未通过校验,最后一轮原因:" + lastFailure);
    }
}
