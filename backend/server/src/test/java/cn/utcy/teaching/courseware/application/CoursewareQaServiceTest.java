package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.ai.media.DashScopeTtsClient;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.infrastructure.CoursewareProperties;
import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.ai.llm.FakeLlm;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.courseware.infrastructure.QaEventEntity;
import cn.utcy.teaching.courseware.infrastructure.QaEventMapper;
import cn.utcy.teaching.ai.structured.SchemaRegistry;
import cn.utcy.teaching.ai.structured.StructuredGenerator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoursewareQaServiceTest {

    private static final Actor STUDENT = new Actor(21L, "student", SystemRole.STUDENT);

    private final FakeLlm llm = new FakeLlm();
    private final CoursewareApplicationService coursewares =
            mock(CoursewareApplicationService.class);
    private final QaEventMapper qaEvents = mock(QaEventMapper.class);
    private final CourseAccess courseAccess = mock(CourseAccess.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final DashScopeTtsClient ttsClient = mock(DashScopeTtsClient.class);
    private final ObjectMapper objectMapper =
            new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final CoursewareQaService service = new CoursewareQaService(
            coursewares,
            new SchemaRegistry(objectMapper, "courseware/schemas",
                    java.util.List.of("scene-blocks", "speech", "answer")),
            new PromptLoader(objectMapper),
            new StructuredGenerator(llm, objectMapper),
            new cn.utcy.teaching.ai.llm.ModelConfig("test", "qwen-test", false, 0.3, 0.9, 1024),
            objectMapper,
            ttsClient,
            CoursewareTestProperties.defaults(),
            qaEvents,
            courseAccess,
            currentActor,
            Runnable::run, // 同线程执行:测试中 SSE 任务行为与同步完全一致
            mock(CourseAiKeys.class),
                mock(LearningEventRecorder.class));

    @BeforeEach
    void actor() {
        when(currentActor.require()).thenReturn(STUDENT);
    }

    private static Stage stage(Stage.Scene... scenes) {
        return new Stage("光的反射", "default", List.of(scenes));
    }

    private static Stage.Scene contentScene() {
        return new Stage.Scene("scene-1", "content", "反射定律", "standard", "反射定律要点",
                List.of(new Block.Paragraph("blk-paragraph-1", "入射角等于反射角。"),
                        new Block.Bullets("blk-bullets-1", false, List.of(
                                new Block.BulletItem("三线共面", null),
                                new Block.BulletItem("两角相等", null)))),
                List.of(new Stage.SpeechSegment("我们来看反射定律。", List.of(), null)),
                List.of(), null);
    }

    @Test
    void 正常回答_动作保留_语音内联_并按学生落问答记录() {
        when(coursewares.getPlayStageInternal(9L, 10L)).thenReturn(stage(contentScene()));
        llm.answerText("""
                {"text":"入射角和反射角总是相等的,你看这一条。",
                 "actions":[{"type":"highlight","target":"blk-bullets-1#2"}]}
                """);
        byte[] audio = "fake-mp3".getBytes(StandardCharsets.UTF_8);
        when(ttsClient.synthesize(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new DashScopeTtsClient.TtsResult(audio, "mp3"));

        CoursewareQaService.Answer answer =
                service.answer("test-key", 21L, 9L, 10L, "scene-1", "为什么两角相等?", e -> { });

        assertThat(answer.text()).contains("相等");
        assertThat(answer.actions()).containsExactly(
                new CoursewareQaService.AnswerAction("highlight", "blk-bullets-1#2"));
        assertThat(answer.audio()).isNotNull();
        assertThat(answer.audio().base64()).isEqualTo(Base64.getEncoder().encodeToString(audio));
        assertThat(answer.audio().format()).isEqualTo("mp3");

        ArgumentCaptor<QaEventEntity> captor = ArgumentCaptor.forClass(QaEventEntity.class);
        verify(qaEvents).insert(captor.capture());
        assertThat(captor.getValue().getCoursewareId()).isEqualTo(10L);
        assertThat(captor.getValue().getAccountId()).isEqualTo(21L);
        assertThat(captor.getValue().getSceneId()).isEqualTo("scene-1");
        assertThat(captor.getValue().getQuestion()).isEqualTo("为什么两角相等?");
        assertThat(captor.getValue().getAnswer()).isEqualTo(answer.text());
    }

    @Test
    void 流式增量_只外送text字段内容_拼接与终文一致() {
        when(coursewares.getPlayStageInternal(9L, 10L)).thenReturn(stage(contentScene()));
        when(ttsClient.synthesize(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("测试不合成"));
        // 模拟任意切分的流式增量(转义符跨块)
        llm.answerInChunks("{\"te", "xt\":\"入射", "角\\", "n等于反射角。\",\"a", "ctions\":[]}");

        List<Map<String, Object>> events = new ArrayList<>();
        CoursewareQaService.Answer answer =
                service.answer("test-key", 21L, 9L, 10L, "scene-1", "两角关系?", events::add);

        String streamed = events.stream()
                .filter(e -> "answer_delta".equals(e.get("type")))
                .map(e -> (String) e.get("text"))
                .reduce("", String::concat);
        assertThat(streamed).isEqualTo("入射角\n等于反射角。");
        assertThat(answer.text()).isEqualTo(streamed);
    }

    @Test
    void 语音合成失败_降级纯文本_回答不受影响() {
        when(coursewares.getPlayStageInternal(9L, 10L)).thenReturn(stage(contentScene()));
        llm.answerText("""
                {"text":"三线共面说的是入射线、反射线和法线在同一平面内。","actions":[]}
                """);
        when(ttsClient.synthesize(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("TTS 超时"));

        CoursewareQaService.Answer answer =
                service.answer("test-key", 21L, 9L, 10L, "scene-1", "什么叫三线共面?", e -> { });

        assertThat(answer.text()).isNotBlank();
        assertThat(answer.audio()).isNull();
        verify(qaEvents).insert(any(QaEventEntity.class));
    }

    @Test
    void 编造目标过半_打回并回喂真实块id清单() {
        when(coursewares.getPlayStageInternal(9L, 10L)).thenReturn(stage(contentScene()));
        when(ttsClient.synthesize(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("测试不合成"));
        llm.answerText("""
                        {"text":"看这里。","actions":[
                          {"type":"highlight","target":"blk-fake-1"},
                          {"type":"highlight","target":"blk-fake-2"}]}
                        """, """
                        {"text":"看这里。","actions":[{"type":"highlight","target":"blk-paragraph-1"}]}
                        """);

        CoursewareQaService.Answer answer =
                service.answer("test-key", 21L, 9L, 10L, "scene-1", "重点在哪?", e -> { });

        assertThat(answer.actions()).hasSize(1);
        assertThat(llm.textCalls).hasSize(2);
        String feedback = llm.textCalls.get(1).stream()
                .map(ChatAgentLoop::textOf).reduce("", (a, b) -> a + "\n" + b);
        assertThat(feedback).contains("blk-paragraph-1").contains("blk-bullets-1");
    }

    @Test
    void 页面不存在_404() {
        when(coursewares.getPlayStageInternal(9L, 10L)).thenReturn(stage(contentScene()));
        assertThatThrownBy(() -> service.answer("test-key", 21L, 9L, 10L, "scene-nope", "?", e -> { }))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void askSse_鉴权在进入异步任务前完成_无权限直接抛出() {
        doThrow(new NotFoundException("课程不存在"))
                .when(courseAccess).requireLearningAccess(9L, STUDENT);
        assertThatThrownBy(() -> service.askSse(9L, 10L, "scene-1", "?"))
                .isInstanceOf(NotFoundException.class);
        verify(coursewares, org.mockito.Mockito.never())
                .getPlayStageInternal(org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong());
    }
}
