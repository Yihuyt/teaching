package cn.utcy.teaching.tutor.application;

import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.ai.llm.FakeLlm;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.tutor.infrastructure.TutorMessageEntity;
import cn.utcy.teaching.tutor.infrastructure.TutorSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TutorContextBuilderTest {

    private static final ModelConfig MODEL = new ModelConfig("t", "test", false, 0.2, 0.9, 8000);

    private final FakeLlm llm = new FakeLlm();
    private final List<String> stored = new ArrayList<>();

    private static TutorMessageEntity message(long id, String role, String content) throws Exception {
        TutorMessageEntity m = new TutorMessageEntity(1L, role, content, "[]", null, LocalDateTime.now());
        Field field = TutorMessageEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(m, id);
        return m;
    }

    private static TutorSessionEntity session(String summary, long watermark) throws Exception {
        TutorSessionEntity s = new TutorSessionEntity(6L, 2L, 7L, LocalDateTime.now());
        Field field = TutorSessionEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(s, 1L);
        s.updateSummary(summary, watermark);
        return s;
    }

    @Test
    void 预算内不摘要_原样带入() throws Exception {
        TutorContextBuilder builder = new TutorContextBuilder(llm, new PromptLoader(new ObjectMapper()), MODEL, 100_000);
        List<TutorMessageEntity> messages = List.of(message(1, "user", "问"), message(2, "assistant", "答"));

        TutorContextBuilder.Result result = builder.build("key", session("", 0), messages,
                (id, summary, upTo) -> stored.add(summary + "@" + upTo));

        assertThat(result.history()).hasExactlyElementsOfTypes(UserMessage.class, AiMessage.class);
        assertThat(llm.textCalls).isEmpty();
        assertThat(stored).isEmpty();
    }

    @Test
    void 超预算_摘要旧消息并前进水位_最近消息保留() throws Exception {
        // 窗口 1000 → 历史预算 350,最近预算 210;每条 120 字
        TutorContextBuilder builder = new TutorContextBuilder(llm, new PromptLoader(new ObjectMapper()), MODEL, 1000);
        List<TutorMessageEntity> messages = List.of(
                message(1, "user", "甲".repeat(120)), message(2, "assistant", "乙".repeat(120)),
                message(3, "user", "丙".repeat(120)), message(4, "assistant", "丁".repeat(120)));
        llm.answerText("目标:学反射");

        TutorContextBuilder.Result result = builder.build("key", session("", 0), messages,
                (id, summary, upTo) -> stored.add(summary + "@" + upTo));

        assertThat(stored).containsExactly("目标:学反射@3");
        assertThat(result.history().get(0)).isInstanceOf(SystemMessage.class);
        assertThat(ChatAgentLoop.textOf(result.history().get(0))).startsWith("[对话摘要]\n目标:学反射");
        assertThat(result.history()).hasSize(2);
        assertThat(ChatAgentLoop.textOf(result.history().get(1))).isEqualTo("丁".repeat(120));
    }

    @Test
    void 摘要失败_本回合降级带原文_水位不前进() throws Exception {
        TutorContextBuilder builder = new TutorContextBuilder(llm, new PromptLoader(new ObjectMapper()), MODEL, 1000);
        List<TutorMessageEntity> messages = List.of(
                message(1, "user", "甲".repeat(120)), message(2, "assistant", "乙".repeat(120)),
                message(3, "user", "丙".repeat(120)), message(4, "assistant", "丁".repeat(120)));
        llm.answerText(request -> {
            throw new IllegalStateException("down");
        });

        TutorContextBuilder.Result result = builder.build("key", session("", 0), messages,
                (id, summary, upTo) -> stored.add(summary));

        assertThat(stored).isEmpty();
        // 硬裁剪到预算内,最新消息一定在
        assertThat(ChatAgentLoop.textOf(result.history().get(result.history().size() - 1))).isEqualTo("丁".repeat(120));
        assertThat(result.tokenCount()).isLessThanOrEqualTo(result.budget());
    }
}
