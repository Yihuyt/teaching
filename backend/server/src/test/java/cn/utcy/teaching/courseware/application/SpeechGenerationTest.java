package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.ai.llm.FakeLlm;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.ai.structured.SchemaRegistry;
import cn.utcy.teaching.ai.structured.StructuredGenerator;
import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.domain.layout.LayoutEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 测验页讲稿不泄底:讲稿模型看到的题块没有答案与讲解(机制),自己解题报答案的讲稿被打回(守门)。
 */
class SpeechGenerationTest {

    private final FakeLlm llm = new FakeLlm();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SceneGenerator generator = new SceneGenerator(
            new StructuredGenerator(llm, objectMapper),
            new SchemaRegistry(objectMapper, "courseware/schemas", List.of("scene-blocks", "speech", "answer")),
            new PromptLoader(objectMapper),
            llm,
            new cn.utcy.teaching.ai.llm.ModelConfig("test", "qwen-test", false, 0.3, 0.9, 1024),
            new LayoutEngine(),
            objectMapper,
            mock(cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage.class),
            CoursewareTestProperties.defaults());

    private static final Stage.Scene QUIZ = new Stage.Scene("scene-3", "quiz", "随堂测验", "quiz", "一道题",
            List.of(new Block.QuizChoice("blk-quiz_choice-1", "2 kg 的物体受 6 N 合外力,加速度是多少?",
                    List.of(new Block.QuizOption("A", "0.3 m/s²"), new Block.QuizOption("B", "3 m/s²")),
                    List.of("B"), false, "由 F=ma 得 a=6/2=3,选 B。")),
            List.of(), List.of(), null);

    private static String speech(String... texts) {
        StringBuilder json = new StringBuilder("{\"speech\":[");
        for (int i = 0; i < texts.length; i++) {
            json.append(i > 0 ? "," : "").append("{\"text\":\"").append(texts[i]).append("\",\"actions\":[]}");
        }
        return json.append("]}").toString();
    }

    @Test
    void 讲稿模型看不到答案与讲解_泄底的讲稿被打回重写() {
        List<List<ChatMessage>> chats = llm.textCalls;
        llm.answerText(speech("我们来看一道题。", "代入公式,所以正确答案是 B。"),
                speech("我们来看一道题。", "想一想合外力、质量和加速度的关系,请作答。"));

        SceneGenerator.Generated generated = generator.generateSpeech("test-key", "课", QUIZ, 3, 3, null,
                text -> { });

        String system = ChatAgentLoop.textOf(chats.get(0).get(0));
        String user = ChatAgentLoop.textOf(chats.get(0).get(1));
        assertThat(system).contains("绝对不要给出答案");
        assertThat(user).contains("2 kg 的物体受 6 N 合外力");
        assertThat(user).doesNotContain("由 F=ma 得").doesNotContain("\"answer\" : [ \"B\" ]");
        // 第一版报了答案 → 打回并说明;第二版通过
        assertThat(chats).hasSize(2);
        String feedback = ChatAgentLoop.textOf(chats.get(1).get(chats.get(1).size() - 1));
        assertThat(feedback).contains("不能给出答案");
        assertThat(generated.scene().speech()).hasSize(2);
        assertThat(generated.scene().speech().get(1).text()).contains("请作答");
        // 落库的页仍保有答案与讲解(剥除只发生在给模型看的投影上)
        assertThat(((Block.QuizChoice) generated.scene().blocks().get(0)).answer()).containsExactly("B");
    }
}
