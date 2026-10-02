package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.ai.structured.SchemaRegistry;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.EditOp;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.domain.StageCommands;
import cn.utcy.teaching.courseware.infrastructure.CoursewareEntity;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StageEditServiceTest {

    /** 与 application.yml 的全局 jackson 口径同形:缺 creator 字段即失败——操作解析必须不受它影响 */
    private final ObjectMapper platformMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final StageJsonCodec codec = new StageJsonCodec(platformMapper);
    private final CoursewareApplicationService coursewares = mock(CoursewareApplicationService.class);
    private final EditOpSchema opSchema = new EditOpSchema(
            new SchemaRegistry(platformMapper, "courseware/schemas", List.of("scene-blocks", "speech", "answer")), platformMapper);
    private final StageEditService service = new StageEditService(coursewares, codec,
            mock(CourseAccess.class), mock(CurrentActor.class), opSchema, platformMapper);

    private static Stage baseStage() {
        Stage.Scene scene = new Stage.Scene("scene-1", "content", "一", "standard", "概要",
                List.of(new Block.Paragraph("blk-paragraph-1", "正文")),
                List.of(new Stage.SpeechSegment("讲稿。", List.of(), "courseware/10/audio/a.wav")), List.of(), null);
        return new Stage("课", "default", List.of(scene));
    }

    private CoursewareEntity locked(Stage stored) {
        CoursewareEntity entity = mock(CoursewareEntity.class);
        when(entity.getId()).thenReturn(10L);
        when(entity.getBody()).thenReturn(codec.toJson(stored));
        when(coursewares.requireForUpdate(6L, 10L)).thenReturn(entity);
        when(coursewares.saveLocked(eq(entity), any())).thenAnswer(inv ->
                new CoursewareApplicationService.VersionedStage(inv.getArgument(1), 3L));
        return entity;
    }

    private ObjectNode op(String name) {
        return platformMapper.createObjectNode().put("op", name);
    }

    @Test
    void 可选字段缺省放行_只给title的元信息修改与不带音频的讲稿段() {
        EditOp meta = service.parse(op("update_stage_meta").put("title", "新标题"));
        assertThat(meta).isInstanceOf(EditOp.UpdateStageMeta.class);

        ObjectNode speech = op("set_speech").put("sceneId", "scene-1");
        speech.putArray("speech").addObject().put("text", "新讲稿。").putArray("actions");
        EditOp parsed = service.parse(speech);
        assertThat(((EditOp.SetSpeech) parsed).speech().get(0).text()).isEqualTo("新讲稿。");

        ObjectNode pin = op("pin_block").put("sceneId", "scene-1").put("blockId", "blk-paragraph-1")
                .put("x", 100).put("y", 200).put("w", 500);
        assertThat(((EditOp.PinBlock) service.parse(pin)).h()).isNull();

        ObjectNode add = op("add_block").put("sceneId", "scene-1").put("index", 1);
        add.putObject("block").put("id", "blk-formula-1").put("type", "formula").put("latex", "F = ma");
        assertThat(((EditOp.AddBlock) service.parse(add)).block()).isInstanceOf(Block.Formula.class);
    }

    @Test
    void 形状违规拒绝_缺必填_多余字段_未知操作_枚举越界() {
        assertThatThrownBy(() -> service.parse(op("delete_scene")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("sceneId");
        assertThatThrownBy(() -> service.parse(op("update_stage_meta").put("title", "x").put("audioPath", "y")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("audioPath");
        ObjectNode speech = op("set_speech").put("sceneId", "scene-1");
        speech.putArray("speech").addObject().put("text", "讲稿。").put("audioPath", "courseware/10/audio/x.wav").putArray("actions");
        assertThatThrownBy(() -> service.parse(speech))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("audioPath");
        assertThatThrownBy(() -> service.parse(op("teleport_scene")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("未知操作");
        assertThatThrownBy(() -> service.parse(op("set_block_size").put("sceneId", "scene-1").put("blockId", "b").put("size", "huge")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("允许值");
    }

    @Test
    void 教师编辑_单次读写_讲稿替换保留原音频_校验失败400给出教师能看懂的提示() {
        CoursewareEntity entity = locked(baseStage());
        ObjectNode speech = op("set_speech").put("sceneId", "scene-1");
        speech.putArray("speech").addObject().put("text", "讲稿。").putArray("actions");
        speech.withArray("speech").addObject().put("text", "补一段。").putArray("actions");

        service.applyForTeacher(6L, 10L, List.of(op("update_stage_meta").put("title", "新课名"), speech));

        ArgumentCaptor<Stage> saved = ArgumentCaptor.forClass(Stage.class);
        verify(coursewares).saveLocked(eq(entity), saved.capture());
        assertThat(saved.getValue().title()).isEqualTo("新课名");
        List<Stage.SpeechSegment> merged = saved.getValue().scenes().get(0).speech();
        assertThat(merged.get(0).audioPath()).isEqualTo("courseware/10/audio/a.wav");
        assertThat(merged.get(1).audioPath()).isNull();
        verify(coursewares).detail(eq(entity), eq(saved.getValue()));

        assertThatThrownBy(() -> service.applyForTeacher(6L, 10L, List.of(op("delete_scene"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("修改内容格式不正确,请刷新页面后重试");
        assertThatThrownBy(() -> service.applyForTeacher(6L, 10L, List.of(op("delete_scene").put("sceneId", "scene-nope"))))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("这一页已不存在,请刷新后重试");
        ObjectNode textBlockWithHeight = op("pin_block").put("sceneId", "scene-1").put("blockId", "blk-paragraph-1")
                .put("x", 0).put("y", 0).put("w", 300).put("h", 100);
        assertThatThrownBy(() -> service.applyForTeacher(6L, 10L, List.of(textBlockWithHeight)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("修改内容格式不正确,请刷新页面后重试");
    }

    @Test
    void 整页落库_覆盖保id_追加分新id_页序从1起() {
        locked(baseStage());
        Stage.Scene regenerated = new Stage.Scene("scene-ignored", "content", "重做的一", "standard", "概要",
                List.of(new Block.Paragraph("blk-paragraph-1", "新正文")),
                List.of(new Stage.SpeechSegment("新讲稿。", List.of(), null)), List.of(), null);

        CoursewareApplicationService.VersionedStage replaced = service.putSceneInternal(6L, 10L, 1, regenerated);
        assertThat(replaced.stage().scenes().get(0).id()).isEqualTo("scene-1");
        assertThat(replaced.stage().scenes().get(0).title()).isEqualTo("重做的一");

        locked(baseStage());
        CoursewareApplicationService.VersionedStage appended = service.putSceneInternal(6L, 10L, 9, regenerated);
        assertThat(appended.stage().scenes()).hasSize(2);
        assertThat(appended.stage().scenes().get(1).id()).isEqualTo("scene-2");

        assertThatThrownBy(() -> service.putSceneInternal(6L, 10L, 0, regenerated))
                .isInstanceOf(StageCommands.Rejected.class).hasMessageContaining("从 1 开始");
    }
}
