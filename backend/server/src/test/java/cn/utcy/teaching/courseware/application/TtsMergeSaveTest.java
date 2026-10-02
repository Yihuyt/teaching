package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.ai.media.DashScopeTtsClient;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage;
import cn.utcy.teaching.courseware.infrastructure.CoursewareEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TTS 回写只合并 audioPath:以库中最新版本为底、锁行写回;
 * 合成期间被编辑过的段不回填 —— 编辑内容绝不被 TTS 覆盖。
 */
class TtsMergeSaveTest {

    private final CoursewareApplicationService coursewares = mock(CoursewareApplicationService.class);
    private final DashScopeTtsClient ttsClient = mock(DashScopeTtsClient.class);
    private final CoursewareAssetStorage assetStorage = mock(CoursewareAssetStorage.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StageJsonCodec codec = new StageJsonCodec(objectMapper);
    private final TtsApplicationService service = new TtsApplicationService(
            coursewares, codec, ttsClient, assetStorage, CoursewareTestProperties.defaults(),
            mock(CourseAccess.class), mock(CurrentActor.class), objectMapper, Runnable::run,
            mock(CourseAiKeys.class), org.springframework.transaction.support.TransactionOperations.withoutTransaction());

    private static Stage stageWith(String seg1Text, String seg2Text) {
        Stage.Scene scene = new Stage.Scene("p1", "content", "讲解", "standard", "概要",
                List.of(new Block.Paragraph("blk-paragraph-1", "正文")),
                List.of(new Stage.SpeechSegment(seg1Text, List.of(), null),
                        new Stage.SpeechSegment(seg2Text, List.of(), null)),
                List.of(), null);
        return new Stage("课", "default", List.of(scene));
    }

    private CoursewareEntity lockedRow(Stage fresh, long version) {
        CoursewareEntity entity = mock(CoursewareEntity.class);
        when(entity.getBody()).thenReturn(codec.toJson(fresh));
        when(entity.getVersion()).thenReturn(version);
        when(coursewares.requireForUpdate(9L, 10L)).thenReturn(entity);
        return entity;
    }

    @Test
    void 合成期间的编辑不被覆盖_已改文本的段不回填() {
        // 合成开始时的课件
        when(coursewares.getInternal(9L, 10L)).thenReturn(stageWith("第一段。", "第二段。"));
        // 合成结束后库里已是编辑过的新版本:第二段文本被改了
        CoursewareEntity locked = lockedRow(stageWith("第一段。", "第二段改过了。"), 7L);
        when(ttsClient.synthesize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new DashScopeTtsClient.TtsResult(new byte[]{1}, "wav"));
        when(assetStorage.put(eq(10L), anyString(), any(), anyString()))
                .thenAnswer(inv -> "courseware/10/audio/" + inv.getArgument(1));
        ArgumentCaptor<Stage> savedStage = ArgumentCaptor.forClass(Stage.class);
        when(coursewares.saveLocked(eq(locked), savedStage.capture()))
                .thenAnswer(inv -> new CoursewareApplicationService.VersionedStage(inv.getArgument(1), 8L));

        TtsApplicationService.Summary summary = service.synthesize("test-key", 9L, 10L,
                (done, total) -> { }, () -> false);

        List<Stage.SpeechSegment> speech = savedStage.getValue().scenes().get(0).speech();
        // 第一段文本未变 → 回填音频;第二段被编辑过 → 不回填,编辑后的文本原样保留
        assertThat(speech.get(0).audioPath()).isNotNull();
        assertThat(speech.get(1).audioPath()).isNull();
        assertThat(speech.get(1).text()).isEqualTo("第二段改过了。");
        assertThat(summary.generated()).isEqualTo(2);
        assertThat(summary.version()).isEqualTo(8L);
    }

    @Test
    void 视频页的讲稿合成后视频原样保留() {
        Stage.Scene video = new Stage.Scene("v1", "video", "实验视频", "standard", "看清楚反射角", List.of(),
                List.of(new Stage.SpeechSegment("请看视频。", List.of(), null)), List.of(), null,
                new Stage.Video("courseware/10/videos/a.mp4"));
        Stage stage = new Stage("课", "default", List.of(video));
        when(coursewares.getInternal(9L, 10L)).thenReturn(stage);
        CoursewareEntity locked = lockedRow(stage, 1L);
        when(ttsClient.synthesize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new DashScopeTtsClient.TtsResult(new byte[]{1}, "wav"));
        when(assetStorage.put(eq(10L), anyString(), any(), anyString()))
                .thenAnswer(inv -> "courseware/10/audio/" + inv.getArgument(1));
        ArgumentCaptor<Stage> savedStage = ArgumentCaptor.forClass(Stage.class);
        when(coursewares.saveLocked(eq(locked), savedStage.capture()))
                .thenAnswer(inv -> new CoursewareApplicationService.VersionedStage(inv.getArgument(1), 2L));

        service.synthesize("test-key", 9L, 10L, (done, total) -> { }, () -> false);

        Stage.Scene saved = savedStage.getValue().scenes().get(0);
        assertThat(saved.speech().get(0).audioPath()).isNotNull();
        assertThat(saved.video().src()).isEqualTo("courseware/10/videos/a.mp4");
    }

    @Test
    void 空串audioPath视同无音频_合成并回填() {
        Stage.Scene scene = new Stage.Scene("p1", "content", "讲解", "standard", "概要",
                List.of(new Block.Paragraph("blk-paragraph-1", "正文")),
                List.of(new Stage.SpeechSegment("第一段。", List.of(), "")),
                List.of(), null);
        Stage stage = new Stage("课", "default", List.of(scene));
        when(coursewares.getInternal(9L, 10L)).thenReturn(stage);
        CoursewareEntity locked = lockedRow(stage, 3L);
        when(ttsClient.synthesize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new DashScopeTtsClient.TtsResult(new byte[]{1}, "wav"));
        when(assetStorage.put(eq(10L), anyString(), any(), anyString()))
                .thenAnswer(inv -> "courseware/10/audio/" + inv.getArgument(1));
        ArgumentCaptor<Stage> savedStage = ArgumentCaptor.forClass(Stage.class);
        when(coursewares.saveLocked(eq(locked), savedStage.capture()))
                .thenAnswer(inv -> new CoursewareApplicationService.VersionedStage(inv.getArgument(1), 4L));

        service.synthesize("test-key", 9L, 10L, (done, total) -> { }, () -> false);

        assertThat(savedStage.getValue().scenes().get(0).speech().get(0).audioPath())
                .startsWith("courseware/10/audio/");
    }
}
