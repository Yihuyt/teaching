package cn.utcy.teaching.question.application;

import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.ai.llm.LlmCalls;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.retrieval.application.RetrievalTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 出题管线对共享检索工具(rag,定义见 {@link RetrievalTools})的接入:
 * 工具 schema、提示词清单、参数校验、检索与渲染都来自共享定义;出题特有的只有
 * 工具结果概括——工具结果先经一次反思压缩(temperature 0.2 / 800 tokens)再回填并进探索轨迹。
 */
final class QuestionTools {

    private static final Logger log = LoggerFactory.getLogger(QuestionTools.class);

    static final double SUMMARIZER_TEMPERATURE = 0.2;
    static final int SUMMARIZER_MAX_TOKENS = 800;
    static final String NO_MOUNT_NOTE = "(未挂载知识库)";

    private final RetrievalTools retrieval;
    private final LlmCalls llm;
    private final PromptLoader prompts;

    QuestionTools(RetrievalTools retrieval, LlmCalls llm, PromptLoader prompts) {
        this.retrieval = retrieval;
        this.llm = llm;
        this.prompts = prompts;
    }

    List<ToolSpecification> definitions(RetrievalTools.Mounts mounts) {
        return retrieval.definitions(mounts);
    }

    static String toolList(RetrievalTools.Mounts mounts) {
        return RetrievalTools.toolList(mounts);
    }

    static String kbNote(RetrievalTools.Mounts mounts) {
        return RetrievalTools.mountNote(mounts, NO_MOUNT_NOTE);
    }

    /**
     * 工具执行器:共享检索 → 反思压缩 → 回填;onNotice 上报概括失败;trace 记录调用参数与概括结果。
     * 编号表按一次出题任务共享(探索与逐题阶段连续编号)。
     */
    ChatAgentLoop.ToolRunner runner(String apiKey, ModelConfig baseModel, long courseId, RetrievalTools.Mounts mounts,
                                    RetrievalTools.SourceRegistry registry, ExplorationTrace trace,
                                    Consumer<String> onNotice) {
        ModelConfig summarizer = new ModelConfig(baseModel.id() + "-summarizer",
                baseModel.providerModel(), false, SUMMARIZER_TEMPERATURE, baseModel.topP(),
                SUMMARIZER_MAX_TOKENS);
        return (callId, name, argumentsJson) -> {
            trace.appendToolCall(name, argumentsJson);
            RetrievalTools.Outcome outcome = retrieval.execute(courseId, mounts, registry, name, argumentsJson);
            if (outcome.isError()) {
                trace.appendToolResult(name, "(参数错误:" + outcome.content() + ")");
                return ChatAgentLoop.ToolResult.error(outcome.content());
            }
            String summarized = summarize(apiKey, summarizer, outcome.content(), onNotice);
            trace.appendToolResult(name, summarized);
            return ChatAgentLoop.ToolResult.of(summarized, outcome.sources());
        };
    }

    private String summarize(String apiKey, ModelConfig summarizer, String raw, Consumer<String> onNotice) {
        try {
            PromptLoader.Prompt prompt = prompts.build("question/prompts", "tool-summarize",
                    Map.of("toolResult", raw));
            String text = llm.chatText(apiKey, summarizer, List.of(
                    SystemMessage.from(prompt.system()), UserMessage.from(prompt.user())), false, null);
            return text == null || text.isBlank() ? raw : text.strip();
        } catch (RuntimeException exception) {
            log.warn("工具结果概括失败,回填原文: {}", exception.getMessage());
            onNotice.accept("工具结果概括失败;将把原始检索结果传给下一轮。");
            return raw;
        }
    }
}
