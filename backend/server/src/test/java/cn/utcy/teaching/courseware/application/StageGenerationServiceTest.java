package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.ai.llm.FakeLlm;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.ai.structured.SchemaRegistry;
import cn.utcy.teaching.ai.structured.StructuredGenerator;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.courseware.application.StageGenerationService.ConfirmedOutline;
import cn.utcy.teaching.courseware.application.StageGenerationService.GeneratedOutline;
import cn.utcy.teaching.courseware.application.StageGenerationService.MaterialImageRef;
import cn.utcy.teaching.courseware.application.StageGenerationService.SceneOutline;
import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.SceneBrief;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.domain.StageValidator;
import cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class StageGenerationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FakeLlm llm = new FakeLlm();
    private final StageGenerationService service = new StageGenerationService(
            new StructuredGenerator(llm, objectMapper),
            new SchemaRegistry(objectMapper, "courseware/schemas", List.of("outline", "scene-blocks", "speech", "answer")),
            new PromptLoader(objectMapper),
            new ModelConfig("test", "qwen-test", false, 0.3, 0.9, 1024),
            objectMapper, new SyncTaskExecutor(),
            mock(CoursewareApplicationService.class), mock(StageEditService.class), mock(SceneGenerator.class),
            mock(CoursewareMaterialBundleService.class), mock(CoursewareImageService.class),
            mock(CoursewareAssetStorage.class), mock(CourseAccess.class), mock(CurrentActor.class),
            mock(CourseAiKeys.class), CoursewareTestProperties.defaults());

    private JsonNode outline(String scenesJson) throws Exception {
        return objectMapper.readTree("{\"title\":\"光学\",\"scenes\":[" + scenesJson + "]}");
    }

    private static final String COVER = "{\"title\":\"封面\",\"type\":\"content\",\"preset\":\"title-cover\",\"summary\":\"课程封面\"}";
    private static final String LESSON = "{\"title\":\"反射\",\"type\":\"content\",\"preset\":\"standard\",\"summary\":\"反射定律\"}";

    @Test
    void 预设按类型归一_非交互页剥除组件规格_未知图片id丢弃() throws Exception {
        MaterialImages images = MaterialImages.of(List.of(
                new SceneGenerator.ImageInput("courseware/bundles/1/a.png", "示意图", 800, 600, "素材", false)));
        JsonNode parsed = outline(COVER + ","
                + "{\"title\":\"讲解\",\"type\":\"content\",\"preset\":\"standard\",\"summary\":\"要点\",\"widgetType\":\"game\","
                + "\"imageIds\":[\"img_1\",\"img_9\",\"img_1\"]},"
                + "{\"title\":\"测验\",\"type\":\"quiz\",\"preset\":\"standard\",\"summary\":\"一道题\"},"
                + "{\"title\":\"实验\",\"type\":\"interactive\",\"preset\":\"media-right\",\"summary\":\"动手\","
                + "\"widgetType\":\"simulation\",\"widgetOutline\":{\"concept\":\"reflection\"}}");

        StructuredGenerator.Refined<GeneratedOutline> refined = service.refineOutline(parsed, images, null, null, null);

        assertThat(refined.errors()).isNull();
        List<cn.utcy.teaching.courseware.domain.SceneBrief> scenes = refined.value().scenes();
        assertThat(scenes.get(1).widgetType()).isNull();
        assertThat(scenes.get(1).imageIds()).containsExactly("img_1");
        assertThat(scenes.get(2).preset()).isEqualTo("quiz");
        assertThat(scenes.get(3).preset()).isEqualTo("standard");
        assertThat(scenes.get(3).widgetOutline().path("concept").asText()).isEqualTo("reflection");
    }

    @Test
    void AI配图只留给讲解页_宽高比归一_空描述即没有配图() throws Exception {
        JsonNode parsed = outline(COVER + ","
                + "{\"title\":\"讲解\",\"type\":\"content\",\"preset\":\"media-right\",\"summary\":\"要点\","
                + "\"illustration\":{\"prompt\":\" 平面镜反射光路示意图,标注用简体中文 \",\"aspectRatio\":\"9:16\"}},"
                + "{\"title\":\"测验\",\"type\":\"quiz\",\"preset\":\"quiz\",\"summary\":\"一道题\","
                + "\"illustration\":{\"prompt\":\"一张图\",\"aspectRatio\":\"1:1\"}},"
                + "{\"title\":\"小结\",\"type\":\"content\",\"preset\":\"standard\",\"summary\":\"回顾\","
                + "\"illustration\":{\"prompt\":\"  \",\"aspectRatio\":\"1:1\"}}");

        StructuredGenerator.Refined<GeneratedOutline> refined = service.refineOutline(parsed, MaterialImages.empty(), null, null, null);

        assertThat(refined.errors()).isNull();
        List<cn.utcy.teaching.courseware.domain.SceneBrief> scenes = refined.value().scenes();
        assertThat(scenes.get(1).illustration()).isEqualTo(new SceneBrief.Illustration("平面镜反射光路示意图,标注用简体中文", "16:9"));
        assertThat(scenes.get(2).illustration()).isNull();
        assertThat(scenes.get(3).illustration()).isNull();
    }

    @Test
    void 确认后的大纲_非讲解页带AI配图或描述为空按400拦截() {
        SceneBrief.Illustration picture = new SceneBrief.Illustration("光路示意图", "16:9");
        SceneOutline lesson = new SceneOutline("讲解", "content", "standard", "要点", List.of(), null, null, List.of(), picture);
        StageGenerationService.validateOutline(new ConfirmedOutline("课", List.of(lesson)));
        assertThatThrownBy(() -> StageGenerationService.validateOutline(new ConfirmedOutline("课", List.of(lesson,
                new SceneOutline("测验", "quiz", "quiz", "一道题", List.of(), null, null, List.of(), picture)))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("不能要 AI 配图");
        assertThatThrownBy(() -> StageGenerationService.validateOutline(new ConfirmedOutline("课", List.of(
                new SceneOutline("讲解", "content", "standard", "要点", List.of(), null, null, List.of(),
                        new SceneBrief.Illustration(" ", "16:9"))))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("缺少画面描述");
    }

    @Test
    void 页数与配比是硬校验_不符即打回() throws Exception {
        JsonNode parsed = outline(COVER + "," + LESSON + ","
                + "{\"title\":\"测验\",\"type\":\"quiz\",\"preset\":\"quiz\",\"summary\":\"一道题\"}");

        StructuredGenerator.Refined<GeneratedOutline> refined = service.refineOutline(parsed, MaterialImages.empty(), 4, 2, 0);

        assertThat(refined.errors()).hasSize(2);
        assertThat(refined.errors().get(0)).contains("要求共 4 页");
        assertThat(refined.errors().get(1)).contains("恰好 2 页测验");
    }

    @Test
    void 交互页缺组件类型即打回() throws Exception {
        JsonNode parsed = outline(COVER + ","
                + "{\"title\":\"实验\",\"type\":\"interactive\",\"preset\":\"standard\",\"summary\":\"动手\"}");

        StructuredGenerator.Refined<GeneratedOutline> refined = service.refineOutline(parsed, MaterialImages.empty(), null, null, null);

        assertThat(refined.errors()).singleElement().asString().contains("widgetType");
    }

    @Test
    void 确认后的大纲结构问题按400拦截() {
        SceneOutline content = new SceneOutline("讲解", "content", "standard", "要点", List.of(), null, null, List.of(), null);
        assertThatThrownBy(() -> StageGenerationService.validateOutline(new ConfirmedOutline("课", List.of(
                new SceneOutline("讲解", "content", "standard", "  ", List.of(), null, null, List.of(), null)))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("缺少内容概要");
        assertThatThrownBy(() -> StageGenerationService.validateOutline(new ConfirmedOutline("课", List.of(
                new SceneOutline("测验", "quiz", "standard", "一道题", List.of(), null, null, List.of(), null)))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("quiz 页的预设固定");
        assertThatThrownBy(() -> StageGenerationService.validateOutline(new ConfirmedOutline("课", List.of(
                new SceneOutline("实验", "interactive", "standard", "动手", List.of(), null, null, List.of(), null)))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("widgetType");
        assertThatThrownBy(() -> StageGenerationService.validateOutline(new ConfirmedOutline("课", List.of(
                new SceneOutline("测验", "quiz", "quiz", "一道题", List.of(), null, null, List.of(new MaterialImageRef(1, "img_1")), null)))))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("不能配图");
        StageGenerationService.validateOutline(new ConfirmedOutline("课", List.of(content)));
    }

    @Test
    void 生成讲稿的目标页_missing只补没有讲稿的页_all全部重写() {
        Stage.Scene spoken = new Stage.Scene("scene-1", "content", "有讲稿", "standard", "a",
                List.of(new Block.Paragraph("blk-paragraph-1", "a")),
                List.of(new Stage.SpeechSegment("讲一句", List.of(), null)), List.of(), null, null);
        Stage.Scene silent = new Stage.Scene("scene-2", "content", "没讲稿", "standard", "b",
                List.of(new Block.Paragraph("blk-paragraph-1", "b")), List.of(), List.of(), null, null);
        Stage stage = new Stage("课", null, List.of(spoken, silent));
        assertThat(StageGenerationService.speechTargets(stage, "missing")).containsExactly(1);
        assertThat(StageGenerationService.speechTargets(stage, "all")).containsExactly(0, 1);
    }

    @Test
    void 三种类型的占位页都能通过整页校验并保留组件规格() throws Exception {
        JsonNode spec = objectMapper.readTree("{\"concept\":\"reflection\"}");
        Stage.Scene content = StageGenerationService.placeholder(
                new SceneOutline("讲解", "content", "standard", "要点", List.of(), null, null, List.of(), null));
        Stage.Scene quiz = StageGenerationService.placeholder(
                new SceneOutline("测验", "quiz", "quiz", "一道题", List.of(), null, null, List.of(), null));
        Stage.Scene interactive = StageGenerationService.placeholder(
                new SceneOutline("实验 <b>", "interactive", "standard", "动手", List.of(), "simulation", spec, List.of(), null));

        for (Stage.Scene scene : List.of(content, quiz, interactive)) {
            assertThat(StageValidator.validateScene(scene)).as(scene.type()).isEmpty();
        }
        assertThat(content.blocks()).singleElement().isInstanceOf(Block.Paragraph.class);
        assertThat(interactive.interactive().widgetType()).isEqualTo("simulation");
        assertThat(interactive.interactive().widgetOutline()).isEqualTo(spec);
        assertThat(interactive.interactive().html()).contains("实验 &lt;b&gt;");
    }
}
