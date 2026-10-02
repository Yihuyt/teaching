package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.ai.llm.FakeLlm;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.ai.structured.SchemaRegistry;
import cn.utcy.teaching.ai.structured.StructuredGenerator;
import cn.utcy.teaching.courseware.domain.InteractiveHtml;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.domain.SceneBrief;
import cn.utcy.teaching.courseware.domain.layout.LayoutEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InteractiveWidgetGenerationTest {

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

    private static final String MINIMAL_HTML =
            "<!DOCTYPE html><html><head><title>t</title></head><body><p>内容</p></body></html>";

    private JsonNode spec(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static SceneBrief interactive(String title, String summary, String widgetType, JsonNode outline) {
        return new SceneBrief(title, "interactive", "standard", summary, List.of(), widgetType, outline, List.of(), null);
    }

    private Stage.Scene generate(SceneBrief brief) {
        return generator.generateContent("test-key", "课", brief, 2, 5, "scene-x", List.of(), null, null,
                text -> { }).scene();
    }

    /** 固定返回给定 HTML,返回的列表随调用记录发给模型的完整对话 */
    private List<List<ChatMessage>> captureChats(String html) {
        llm.answerText(html);
        return llm.textCalls;
    }

    @Test
    void simulation路由到专属模板_规格变量注入_规格随页落库() {
        var chats = captureChats(MINIMAL_HTML);
        JsonNode outline = spec("{\"concept\":\"projectile_motion\",\"keyVariables\":[\"angle\",\"velocity\"]}");

        Stage.Scene scene = generate(interactive("抛体实验", "动手调角度和初速", "simulation", outline));

        assertThat(scene.interactive().html()).isEqualTo(MINIMAL_HTML);
        assertThat(scene.interactive().widgetType()).isEqualTo("simulation");
        assertThat(scene.interactive().widgetOutline()).isEqualTo(outline);
        String system = ChatAgentLoop.textOf(chats.get(0).get(0));
        String user = ChatAgentLoop.textOf(chats.get(0).get(1));
        assertThat(system).contains("重置按钮——必须真正复位").contains("动画必须肉眼可见");
        assertThat(user).contains("projectile_motion").contains("angle、velocity");
    }

    @Test
    void game路由到专属模板_挑战与玩家控制注入() {
        var chats = captureChats(MINIMAL_HTML);
        generate(interactive("着陆挑战", "控制推力安全着陆", "game",
                spec("{\"gameType\":\"action\",\"challenge\":\"以低于 5m/s 的速度着陆\",\"playerControls\":[\"thrust_slider\"]}")));

        String system = ChatAgentLoop.textOf(chats.get(0).get(0));
        String user = ChatAgentLoop.textOf(chats.get(0).get(1));
        assertThat(system).contains("是游戏,不是测验").contains("公平开局");
        assertThat(user).contains("以低于 5m/s 的速度着陆").contains("thrust_slider");
    }

    @Test
    void 无widgetType走通用模板() {
        var chats = captureChats(MINIMAL_HTML);
        Stage.Scene scene = generate(interactive("动手实验", "交互演示", null, null));

        assertThat(scene.interactive().html()).isEqualTo(MINIMAL_HTML);
        assertThat(ChatAgentLoop.textOf(chats.get(0).get(0))).contains("自主动手操作");
    }

    @Test
    void 含公式时注入KaTeX_定界符归一_script内容不动() {
        String html = "<!DOCTYPE html><html><head></head><body>"
                + "<p>能量公式 $E=mc^2$ 与 $$F=ma$$</p>"
                + "<script>var price = '$5 + $10';</script>"
                + "</body></html>";
        captureChats(html);

        String out = generate(interactive("公式页", "讲公式", "simulation", spec("{}"))).interactive().html();

        assertThat(out).contains("\\(E=mc^2\\)").contains("\\[F=ma\\]");
        assertThat(out).contains("cdn.jsdelivr.net/npm/katex");
        assertThat(out).contains("var price = '$5 + $10';");
    }

    @Test
    void 无公式不注入KaTeX_保持纯自包含() {
        captureChats(MINIMAL_HTML);
        assertThat(generate(interactive("普通页", "无公式", "simulation", spec("{}"))).interactive().html())
                .doesNotContain("katex");
    }

    @Test
    void 白名单外主机被拒_反馈后修正通过() {
        String bad = "<!DOCTYPE html><html><head>"
                + "<script src=\"https://evil.example.com/lib.js\"></script>"
                + "</head><body></body></html>";
        List<List<ChatMessage>> chats = llm.textCalls;
        llm.answerText(bad, MINIMAL_HTML);

        Stage.Scene scene = generate(interactive("实验", "演示", "simulation", spec("{}")));

        assertThat(chats).hasSize(2);
        String feedback = ChatAgentLoop.textOf(chats.get(1).get(chats.get(1).size() - 1));
        assertThat(feedback).contains("evil.example.com");
        assertThat(scene.interactive().html()).isEqualTo(MINIMAL_HTML);
    }

    @Test
    void 三轮未通过即失败_不降级为讲解页() {
        captureChats("这里没有 html 文档");

        assertThatThrownBy(() -> generate(interactive("实验", "演示", "simulation", spec("{}"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("3 轮未通过");
    }

    @Test
    void 白名单校验_CDN与命名空间放行_野链接与被禁能力拦截() {
        assertThat(InteractiveHtml.validate(
                "<!DOCTYPE html><html><head><script type=\"importmap\">"
                        + "{\"imports\":{\"three\":\"https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.js\"}}"
                        + "</script></head><body>"
                        + "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"
                        + "</body></html>")).isEmpty();
        assertThat(InteractiveHtml.validate(
                "<html><head><link href=\"https://my-cdn.example.io/x.css\"></head></html>"))
                .anySatisfy(e -> {
                    assertThat(e.forModel()).contains("my-cdn.example.io");
                    assertThat(e.forTeacher()).isEqualTo("网页引用了不允许的外部地址:my-cdn.example.io。"
                            + "外部库只能来自 cdn.jsdelivr.net、unpkg.com、cdnjs.cloudflare.com");
                });
        assertThat(InteractiveHtml.validate(
                "<html><body><script>fetch('/api')</script></body></html>"))
                .anySatisfy(e -> {
                    assertThat(e.forModel()).contains("被禁能力");
                    assertThat(e.forTeacher()).startsWith("网页里不能联网读取数据或嵌入其他网页");
                });
        assertThat(InteractiveHtml.validate(
                "<html><body><iframe src=\"a.html\"></iframe></body></html>"))
                .anySatisfy(e -> assertThat(e.forModel()).contains("被禁能力"));
    }
}
