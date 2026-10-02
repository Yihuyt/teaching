package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.ai.llm.FakeLlm;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.ai.structured.SchemaRegistry;
import cn.utcy.teaching.ai.structured.StructuredGenerator;
import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.SceneBrief;
import cn.utcy.teaching.courseware.domain.layout.LayoutEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 按大纲专门画的配图必须放上页面:模型漏掉就打回并点名,放上后短 id 解析成对象键并补真实尺寸。
 */
class SceneIllustrationTest {

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

    private static final String WITHOUT_IMAGE = "{\"blocks\":[{\"id\":\"blk-heading-1\",\"type\":\"heading\",\"level\":2,\"text\":\"反射定律\"},"
            + "{\"id\":\"blk-bullets-1\",\"type\":\"bullets\",\"items\":[{\"text\":\"入射角等于反射角\"}]}]}";
    private static final String WITH_IMAGE = "{\"blocks\":[{\"id\":\"blk-heading-1\",\"type\":\"heading\",\"level\":2,\"text\":\"反射定律\"},"
            + "{\"id\":\"blk-bullets-1\",\"type\":\"bullets\",\"items\":[{\"text\":\"入射角等于反射角\"}]},"
            + "{\"id\":\"blk-image-1\",\"type\":\"image\",\"src\":\"img_1\",\"caption\":\"光路示意图\"}]}";

    @Test
    void 专门画的配图漏放就打回_放上后解析成对象键() {
        List<List<ChatMessage>> chats = llm.textCalls;
        llm.answerText(WITHOUT_IMAGE, WITH_IMAGE);
        SceneBrief brief = new SceneBrief("反射", "content", "standard", "反射定律", List.of("入射角等于反射角"),
                null, null, List.of(), null);
        SceneGenerator.ImageInput drawn = new SceneGenerator.ImageInput("courseware/9/images/gen-abc.png",
                "平面镜反射光路示意图", 1664, 928, "按大纲为本页专门画的配图", true);

        SceneGenerator.Generated generated = generator.generateContent("test-key", "光学", brief, 2, 5, "scene-2",
                List.of(drawn), null, null, text -> { });

        String user = ChatAgentLoop.textOf(chats.get(0).get(1));
        assertThat(user).contains("**img_1**:按大纲为本页专门画的配图").contains("必须放上本页");
        assertThat(chats).hasSize(2);
        String feedback = ChatAgentLoop.textOf(chats.get(1).get(chats.get(1).size() - 1));
        assertThat(feedback).contains("图片 img_1 是按大纲为本页专门画的,必须放上页面");
        Block.Image placed = (Block.Image) generated.scene().blocks().get(2);
        assertThat(placed.src()).isEqualTo("courseware/9/images/gen-abc.png");
        assertThat(placed.width()).isEqualTo(1664);
        assertThat(placed.height()).isEqualTo(928);
        assertThat(placed.caption()).isEqualTo("光路示意图");
    }
}
