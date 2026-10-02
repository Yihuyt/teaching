package cn.utcy.teaching.tutor.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.learning.LearningEvent;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.learning.LearningEventType;
import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.ai.llm.FakeLlm;
import cn.utcy.teaching.ai.llm.ScriptedStreamingChatModel;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService.RetrievedPassage;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService.KnowledgeBaseRef;
import cn.utcy.teaching.retrieval.application.RetrievalTools;
import cn.utcy.teaching.tutor.infrastructure.TutorMessageEntity;
import cn.utcy.teaching.tutor.infrastructure.TutorProperties;
import cn.utcy.teaching.tutor.infrastructure.TutorSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TutorChatServiceTest {

    private static final ModelConfig MODEL = new ModelConfig("t", "test", false, 0.2, 0.9, 8000);

    private final ObjectMapper json = new ObjectMapper();
    private final TutorSessionService sessions = mock(TutorSessionService.class);
    private final KnowledgeBaseRetrievalService kbRetrieval = mock(KnowledgeBaseRetrievalService.class);
    private final ScriptedStreamingChatModel.Turn[] scripted = new ScriptedStreamingChatModel.Turn[1];
    private final ScriptedStreamingChatModel model = new ScriptedStreamingChatModel((request, out) -> scripted[0].play(request, out));
    private final FakeLlm llm = new FakeLlm(model);
    private final LearningEventRecorder learningEvents = mock(LearningEventRecorder.class);
    private final List<Map<String, Object>> events = new ArrayList<>();

    private TutorChatService service() {
        TutorProperties properties = new TutorProperties("test", 0.2, 0.9, 8000, false, 8, 100_000);
        return new TutorChatService(sessions, mock(TutorAssistantService.class),
                new RetrievalTools(kbRetrieval, json),
                llm.models(), llm, new PromptLoader(json), MODEL, properties,
                mock(CourseAiKeys.class), mock(CurrentActor.class),
                learningEvents, json, Runnable::run, Runnable::run);
    }

    private static String system(ChatRequest request) {
        return ChatAgentLoop.textOf(request.messages().get(0));
    }

    private static String lastText(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        return ChatAgentLoop.textOf(messages.get(messages.size() - 1));
    }

    private TutorMessageEntity entity(long id, String role, String content) throws Exception {
        TutorMessageEntity m = new TutorMessageEntity(1L, role, content, "[]", null, LocalDateTime.now());
        var field = TutorMessageEntity.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(m, id);
        return m;
    }

    @Test
    void 完整回合() throws Exception {
        TutorMounts mounts = new TutorMounts("光学答疑", "回答时先给结论。", MODEL, 8,
                List.of(new KnowledgeBaseRef(3L, "光学知识库")));
        when(sessions.listMessages(1L)).thenReturn(List.of());
        when(sessions.requireInCourse(6L, 1L)).thenReturn(new TutorSessionEntity(6L, 2L, 7L, LocalDateTime.now()));
        AtomicInteger ids = new AtomicInteger();
        when(sessions.append(eq(1L), anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> entity(ids.incrementAndGet(), inv.getArgument(1), inv.getArgument(2)));
        when(kbRetrieval.retrieve(eq(6L), eq(3L), anyString(), anyInt())).thenReturn(List.of(
                new RetrievedPassage(3L, "光学知识库", "讲义.pdf", "3.1", "反射角等于入射角。")));
        llm.answerText("光的反射定律");

        List<String> systems = new ArrayList<>();
        List<String> users = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        scripted[0] = (request, out) -> {
            systems.add(system(request));
            users.add(lastText(request));
            if (calls.incrementAndGet() == 1) {
                assertThat(request.toolSpecifications()).extracting(ToolSpecification::name).containsExactly("rag");
                out.text("我再查一下知识库。")
                        .toolCall("c1", "rag", "{\"query\":\"光的反射定律\",\"kb_name\":\"光学知识库\"}");
                return;
            }
            out.text("反射角等于入射角[source-1],先学光的直线传播[source-2]。");
        };

        service().run("key", new TutorChatService.Turn(6L, 1L, 7L, "学反射之前要先会什么?", mounts),
                model, events::add, () -> false);

        // 预检索块接在用户消息末尾,带编号;system 含助手名、挂载说明、工具清单与教师补充要求
        assertThat(users.get(0)).startsWith("学反射之前要先会什么?\n\n[Knowledge Base Context]")
                .contains("## 光学知识库\n[source-1] 讲义.pdf › 3.1\n反射角等于入射角。");
        assertThat(systems.get(0)).contains("学习助手「光学答疑」")
                .contains("已挂载知识库:光学知识库")
                .contains("- `rag` —")
                .contains("## teacher").contains("回答时先给结论。");
        List<String> types = events.stream().map(e -> String.valueOf(e.get("type"))).toList();
        assertThat(types).containsSubsequence("user_message", "seed_sources", "round", "content", "round", "tool",
                "tool", "round", "content", "round", "done", "title");
        // 落库只存 finish 轮文本,来源为 2 条(预检索 1 + 工具 1)
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(sessions).append(eq(1L), eq("assistant"), content.capture(), anyString(), anyString());
        assertThat(content.getValue()).isEqualTo("反射角等于入射角[source-1],先学光的直线传播[source-2]。");
        Map<String, Object> done = events.stream().filter(e -> "done".equals(e.get("type"))).findFirst().orElseThrow();
        assertThat((List<?>) done.get("sources")).hasSize(2);
        ArgumentCaptor<LearningEvent> event = ArgumentCaptor.forClass(LearningEvent.class);
        verify(learningEvents).record(event.capture());
        assertThat(event.getValue().type()).isEqualTo(LearningEventType.TUTOR_QUESTION_ASKED);
        verify(sessions).updateTitle(1L, "光的反射定律");
    }

    @Test
    void 无挂载时不挂工具不预检索() throws Exception {
        when(sessions.listMessages(1L)).thenReturn(List.of());
        when(sessions.requireInCourse(6L, 1L)).thenReturn(new TutorSessionEntity(6L, 2L, 7L, LocalDateTime.now()));
        when(sessions.append(eq(1L), anyString(), anyString(), anyString(), any()))
                .thenAnswer(inv -> entity(1, inv.getArgument(1), inv.getArgument(2)));
        llm.answerText("标题");
        scripted[0] = (request, out) -> {
            assertThat(request.toolSpecifications()).isNullOrEmpty();
            assertThat(lastText(request)).isEqualTo("你好");
            assertThat(system(request)).contains("本助手未挂载知识库").doesNotContain("## teacher");
            out.text("你好!");
        };
        service().run("key", new TutorChatService.Turn(6L, 1L, 7L, "你好",
                new TutorMounts("通用助手", "", MODEL, 8, List.of())), model, events::add, () -> false);
        assertThat(events.stream().map(e -> e.get("type"))).doesNotContain("seed_sources");
        // 谁提问就记谁的学情,不区分角色
        verify(learningEvents).record(any());
    }
}
