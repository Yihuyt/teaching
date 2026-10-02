package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage;
import cn.utcy.teaching.courseware.infrastructure.CoursewareEntity;
import cn.utcy.teaching.courseware.infrastructure.CoursewareMapper;
import cn.utcy.teaching.courseware.infrastructure.CoursewareRowPurger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 课件写入时的对象释放是精确的"旧引用 − 新引用":不再引用的音频 / 图片入删除队列,
 * 从不按前缀清扫——刚拷入、尚未放上页面的配图必须活到被生成的页或教师的编辑引用。
 */
class CoursewareAssetReleaseTest {

    private final CoursewareMapper mapper = mock(CoursewareMapper.class);
    private final CoursewareAssetStorage storage = mock(CoursewareAssetStorage.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StageJsonCodec codec = new StageJsonCodec(objectMapper);
    private final CoursewareApplicationService service = new CoursewareApplicationService(
            mapper, codec, storage, mock(CourseAccess.class), mock(CurrentActor.class), mock(CoursewareRowPurger.class));

    private static Stage stage(String imageSrc, String audioPath) {
        List<Block> blocks = imageSrc == null
                ? List.of(new Block.Paragraph("blk-paragraph-1", "正文"))
                : List.of(new Block.Paragraph("blk-paragraph-1", "正文"),
                        new Block.Image("blk-image-1", imageSrc, 800, 600, null));
        Stage.Scene scene = new Stage.Scene("scene-1", "content", "讲解", "standard", "概要", blocks,
                List.of(new Stage.SpeechSegment("讲稿。", List.of(), audioPath)), List.of(), null);
        return new Stage("课", "default", List.of(scene));
    }

    private CoursewareEntity locked(Stage stored) {
        CoursewareEntity entity = mock(CoursewareEntity.class);
        when(entity.getId()).thenReturn(10L);
        when(entity.getBody()).thenReturn(codec.toJson(stored));
        when(entity.getVersion()).thenReturn(2L);
        when(mapper.updateById(entity)).thenReturn(1);
        return entity;
    }

    @Test
    @SuppressWarnings("unchecked")
    void 不再引用的图片与音频入队_仍引用的不动() {
        CoursewareEntity entity = locked(stage("courseware/10/images/old.png", "courseware/10/audio/old.wav"));

        service.saveLocked(entity, stage("courseware/10/images/new.png", "courseware/10/audio/old.wav"));

        ArgumentCaptor<Collection<String>> released = ArgumentCaptor.forClass(Collection.class);
        verify(storage).enqueueDelete(released.capture());
        assertThat(released.getValue()).containsExactly("courseware/10/images/old.png");
    }

    @Test
    void 只增不减的写入不入队_未被引用的新生成对象不受波及() {
        CoursewareEntity entity = locked(stage(null, null));

        service.saveLocked(entity, stage("courseware/10/images/gen-1.png", "courseware/10/audio/a.wav"));

        verify(storage, never()).enqueueDelete(any());
    }

    @Test
    void 写入只做结构校验_未触及页的历史遗留问题不挡本次写入() {
        // 库里第 2 页讲稿含 LaTeX(规则加严前落库的);本次只改第 1 页
        Stage.Scene stale = new Stage.Scene("scene-2", "content", "旧页", "standard", null,
                List.of(new Block.Paragraph("blk-paragraph-1", "正文")),
                List.of(new Stage.SpeechSegment("代入 $F=ma$。", List.of(), null)), List.of(), null);
        Stage.Scene edited = stage(null, null).scenes().get(0);
        Stage before = new Stage("课", "default", List.of(edited, stale));
        CoursewareEntity entity = locked(before);
        Stage after = new Stage("课", "default", List.of(
                new Stage.Scene("scene-1", "content", "改过的标题", "standard", "概要", edited.blocks(), edited.speech(), List.of(), null),
                stale));

        assertThat(service.saveLocked(entity, after).version()).isEqualTo(2L);
        assertThatThrownBy(() -> service.saveLocked(entity, new Stage("课", "default", List.of(edited, edited))))
                .isInstanceOf(cn.utcy.teaching.shared.error.BadRequestException.class).hasMessageContaining("页面 id 重复");
    }

    @Test
    void 引用别的课件前缀下的对象键_写入被400拒绝() {
        CoursewareEntity entity = locked(stage(null, null));
        assertThatThrownBy(() -> service.saveLocked(entity, stage("courseware/999/images/x.png", null)))
                .isInstanceOf(cn.utcy.teaching.shared.error.BadRequestException.class)
                .hasMessageContaining("不属于本课件");
        assertThatThrownBy(() -> service.saveLocked(entity, stage(null, "courseware/999/audio/x.wav")))
                .isInstanceOf(cn.utcy.teaching.shared.error.BadRequestException.class)
                .hasMessageContaining("不属于本课件");
    }

    @Test
    void 预签名只签本课件前缀下的键_历史越界键静默失效() throws Exception {
        when(storage.presignGet(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.net.URI.create("https://oss.example/signed").toURL());
        Stage.Scene scene = new Stage.Scene("scene-1", "content", "一", "standard", "概要",
                List.of(new Block.Image("blk-image-1", "courseware/10/images/own.png", 800, 600, null),
                        new Block.Image("blk-image-2", "courseware/11/images/foreign.png", 800, 600, null)),
                List.of(new Stage.SpeechSegment("讲。", List.of(), "courseware/12/audio/foreign.wav")), List.of(), null);
        Stage mixed = new Stage("课", "default", List.of(scene));

        assertThat(service.assetUrls(10L, mixed).keySet())
                .containsExactly("courseware/10/images/own.png");
    }
}
