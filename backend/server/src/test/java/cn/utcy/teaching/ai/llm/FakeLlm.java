package cn.utcy.teaching.ai.llm;

import cn.utcy.teaching.ai.infrastructure.DashScopeProperties;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 测试用的模型调用入口:带工具的循环真走 {@link LlmCalls#chat} 驱动脚本化的假模型;
 * 单次文本调用(摘要、标题、规划)按给定答案返回并记录收到的消息。
 */
public final class FakeLlm extends LlmCalls {

    public static final DashScopeProperties PROPERTIES = new DashScopeProperties(
            URI.create("http://localhost/v1"), URI.create("http://localhost/api"), Duration.ofSeconds(1), Duration.ofSeconds(30));

    public final List<List<ChatMessage>> textCalls = new ArrayList<>();
    public final List<Boolean> jsonModes = new ArrayList<>();
    private Function<List<ChatMessage>, String> textAnswer = messages -> "";
    private List<String> chunks;

    public FakeLlm(StreamingChatModel model) {
        super(new FixedModels(model), PROPERTIES);
    }

    public FakeLlm() {
        this(new ScriptedStreamingChatModel((request, out) -> {
            throw new UnsupportedOperationException("测试里没有流式模型");
        }));
    }

    public FakeLlm answerText(String... answers) {
        int[] index = {0};
        textAnswer = messages -> answers[Math.min(index[0]++, answers.length - 1)];
        return this;
    }

    public FakeLlm answerText(Function<List<ChatMessage>, String> answer) {
        textAnswer = answer;
        return this;
    }

    public FakeLlm answerInChunks(String... pieces) {
        chunks = List.of(pieces);
        textAnswer = messages -> String.join("", pieces);
        return this;
    }

    @Override
    public String chatText(String apiKey, ModelConfig config, List<ChatMessage> messages, boolean jsonMode,
                           Consumer<String> onDelta) {
        textCalls.add(new ArrayList<>(messages));
        jsonModes.add(jsonMode);
        String answer = textAnswer.apply(messages);
        if (onDelta != null) {
            (chunks == null ? List.of(answer) : chunks).forEach(onDelta);
        }
        return answer;
    }

    private static final class FixedModels extends LlmModels {
        private final StreamingChatModel model;

        FixedModels(StreamingChatModel model) {
            super(PROPERTIES);
            this.model = model;
        }

        @Override
        public StreamingChatModel chatModel(String apiKey, ModelConfig config) {
            return model;
        }

        @Override
        public EmbeddingModel embeddingModel(String apiKey, String model, int dimension) {
            throw new UnsupportedOperationException("测试里没有向量化模型");
        }
    }
}
