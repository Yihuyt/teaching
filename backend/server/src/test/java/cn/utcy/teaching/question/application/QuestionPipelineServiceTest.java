package cn.utcy.teaching.question.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.ai.llm.FakeLlm;
import cn.utcy.teaching.ai.llm.ScriptedStreamingChatModel;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService.KnowledgeBaseRef;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService.RetrievedPassage;
import cn.utcy.teaching.question.domain.CourseQuestion;
import cn.utcy.teaching.retrieval.application.RetrievalTools;
import cn.utcy.teaching.question.domain.CourseQuestionType;
import cn.utcy.teaching.question.infrastructure.CourseQuestionMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuestionPipelineServiceTest {

    private static final ModelConfig MODEL =
            new ModelConfig("q", "qwen-test", false, 0.4, 0.9, 2048);

    private final ObjectMapper json = new ObjectMapper();
    private final ScriptedStreamingChatModel.Turn[] scripted = new ScriptedStreamingChatModel.Turn[1];
    private final ScriptedStreamingChatModel model = new ScriptedStreamingChatModel((request, out) -> scripted[0].play(request, out));
    private final FakeLlm llm = new FakeLlm(model);
    private final KnowledgeBaseRetrievalService retrieval = mock(KnowledgeBaseRetrievalService.class);
    private final CourseQuestionMapper questions = mock(CourseQuestionMapper.class);
    private final QuestionAttachments attachments = mock(QuestionAttachments.class);
    private final ExamMimicSource mimicSource = mock(ExamMimicSource.class);
    private final List<Map<String, Object>> events = new ArrayList<>();

    private QuestionPipelineService service() {
        // 单独运行时 MyBatis-Plus 的 lambda 列缓存尚未初始化(loadExistingTitles 用到 CourseQuestion::getTitle)
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), CourseQuestion.class);
        when(questions.selectPage(any(), any())).thenReturn(new Page<CourseQuestion>());
        return new QuestionPipelineService(
                llm.models(), llm, new PromptLoader(json), MODEL, questions,
                new QuestionDraftNormalizer(json, new CourseQuestionContract(json)), retrieval,
                new RetrievalTools(retrieval, json),
                attachments, mimicSource, mock(CourseAccess.class), mock(CurrentActor.class), mock(CourseAiKeys.class),
                json, Runnable::run, Runnable::run);
    }

    private static QuestionPipelineService.Job custom(String requirement, String difficulty,
                                                      Map<CourseQuestionType, Integer> counts, int total,
                                                      List<KnowledgeBaseRef> knowledgeBases,
                                                      List<QuestionAttachments.Attachment> files) {
        return new QuestionPipelineService.Job(QuestionPipelineService.Mode.CUSTOM, 6L, requirement, difficulty,
                counts, total, new RetrievalTools.Mounts(knowledgeBases), files, null, 0);
    }

    private static String questionJson(String title, String answer) {
        return "{\"title\":\"" + title + "\",\"type\":\"single_choice\",\"stemMarkdown\":\"题干?\","
                + "\"options\":[\"甲\",\"乙\",\"丙\",\"丁\"],\"answer\":\"" + answer + "\",\"analysisMarkdown\":\"解析。\"}";
    }

    private static int toolCount(ChatRequest request) {
        return request.toolSpecifications() == null ? 0 : request.toolSpecifications().size();
    }

    private static ChatMessage last(ChatRequest request) {
        return request.messages().get(request.messages().size() - 1);
    }

    private void stubTextCalls(String planJson) {
        llm.answerText(messages -> {
            String system = ChatAgentLoop.textOf(messages.get(0));
            if (system.contains("原始工具结果")) {
                return "概括:反射角等于入射角 [source-1]";
            }
            if (system.contains("出题规划器")) {
                return planJson;
            }
            return "{\"items\":[" + questionJson("修复后的题", "甲") + "]}";
        });
    }

    @Test
    void 探索检索概括回填_轨迹进入规划与逐题_题目逐道交付() {
        stubTextCalls("{\"analysis\":\"两道单选\",\"templates\":["
                + "{\"question_id\":\"q_1\",\"topic\":\"反射角与入射角\",\"question_type\":\"single_choice\",\"difficulty\":\"easy\"},"
                + "{\"question_id\":\"q_2\",\"topic\":\"法线的定义\",\"question_type\":\"single_choice\",\"difficulty\":\"easy\"}]}");
        when(retrieval.retrieve(eq(6L), eq(1L), eq("光的反射定律"), anyInt())).thenReturn(List.of(
                new RetrievedPassage(1L, "光学知识库", "讲义.md", "反射", "反射角等于入射角")));
        AtomicInteger modelCall = new AtomicInteger();
        List<Integer> toolCounts = new ArrayList<>();
        scripted[0] = (request, out) -> {
            toolCounts.add(toolCount(request));
            int call = modelCall.incrementAndGet();
            if (call == 1) {
                out.text("先查一下反射定律。")
                        .toolCall("call_1", "rag", "{\"query\":\"光的反射定律\",\"kb_name\":\"光学知识库\"}");
            } else if (call == 2) {
                assertThat(last(request)).isInstanceOf(ToolExecutionResultMessage.class);
                assertThat(ChatAgentLoop.textOf(last(request))).startsWith("概括:");
                out.text("本轮将围绕反射定律出两道题。");
            } else {
                String user = ChatAgentLoop.textOf(last(request));
                assertThat(user).contains("工具结果(已概括):rag").contains("完整规划");
                out.text(questionJson(call == 3 ? "反射角与入射角" : "法线的定义", "A"));
            }
        };

        service().run("key", custom("光的反射定律", "easy",
                new LinkedHashMap<>(Map.of(CourseQuestionType.SINGLE_CHOICE, 2)), 2,
                List.of(new KnowledgeBaseRef(1L, "光学知识库")), List.of()), model, events::add, () -> false);

        List<String> types = events.stream().map(e -> String.valueOf(e.get("type"))).toList();
        assertThat(types).containsSubsequence("stage", "explore_text", "tool", "tool", "stage", "plan",
                "stage", "question", "question", "done");
        assertThat(types).doesNotContain("notice");
        assertThat(toolCounts).allMatch(count -> count == 1);
        String planUser = llm.textCalls.stream()
                .filter(m -> ChatAgentLoop.textOf(m.get(0)).contains("出题规划器")).findFirst()
                .map(m -> ChatAgentLoop.textOf(m.get(1))).orElseThrow();
        assertThat(planUser).contains("迭代 1 —— 工具调用:rag").contains("概括:反射角等于入射角");
        Map<String, Object> done = events.getLast();
        assertThat(done.get("total")).isEqualTo(2);
        QuestionDraftNormalizer.DraftQuestion first = (QuestionDraftNormalizer.DraftQuestion)
                events.stream().filter(e -> "question".equals(e.get("type"))).findFirst().orElseThrow().get("draft");
        assertThat(first.answer().asText()).isEqualTo("甲");
        assertThat(first.issues()).isEmpty();
    }

    @Test
    void 无知识库时不挂工具且探索照跑() {
        stubTextCalls("{\"analysis\":\"\",\"templates\":["
                + "{\"question_id\":\"q_1\",\"topic\":\"顶点坐标\",\"question_type\":\"single_choice\",\"difficulty\":\"medium\"}]}");
        List<Integer> toolCounts = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        scripted[0] = (request, out) -> {
            toolCounts.add(toolCount(request));
            out.text(calls.incrementAndGet() == 1 ? "直接出题。" : questionJson("顶点坐标", "乙"));
        };

        service().run("key", custom("二次函数", "medium",
                new LinkedHashMap<>(Map.of(CourseQuestionType.SINGLE_CHOICE, 1)), 1,
                List.of(), List.of()), model, events::add, () -> false);

        assertThat(toolCounts).containsExactly(0, 0);
        verify(retrieval, never()).retrieve(anyLong(), anyLong(), anyString(), anyInt());
        assertThat(events.getLast().get("type")).isEqualTo("done");
    }

    @Test
    void 规划数量不符发notice并按实际继续_不合格题目修复一次() {
        stubTextCalls("{\"analysis\":\"\",\"templates\":["
                + "{\"question_id\":\"q_1\",\"topic\":\"顶点坐标\",\"question_type\":\"single_choice\",\"difficulty\":\"easy\"}]}");
        AtomicInteger calls = new AtomicInteger();
        scripted[0] = (request, out) -> {
            out.text(calls.incrementAndGet() == 1 ? "探索完毕。"
                    : "{\"title\":\"顶点坐标\",\"type\":\"single_choice\",\"stemMarkdown\":\"题干?\","
                    + "\"options\":[\"甲\",\"乙\"],\"answer\":\"戊\",\"analysisMarkdown\":\"解析。\"}");
        };

        service().run("key", custom("二次函数", "easy",
                new LinkedHashMap<>(Map.of(CourseQuestionType.SINGLE_CHOICE, 2)), 2,
                List.of(), List.of()), model, events::add, () -> false);

        List<String> notices = events.stream().filter(e -> "notice".equals(e.get("type")))
                .map(e -> String.valueOf(e.get("message"))).toList();
        assertThat(notices).anyMatch(m -> m.contains("规划返回了 1 个 template"));
        assertThat(notices).anyMatch(m -> m.contains("做一次格式修复"));
        QuestionDraftNormalizer.DraftQuestion draft = (QuestionDraftNormalizer.DraftQuestion)
                events.stream().filter(e -> "question".equals(e.get("type"))).findFirst().orElseThrow().get("draft");
        assertThat(draft.title()).isEqualTo("修复后的题");
        assertThat(draft.issues()).isEmpty();
    }

    @Test
    void 附件全文拼进出题要求_附件清单进探索提示词() {
        stubTextCalls("{\"analysis\":\"\",\"templates\":["
                + "{\"question_id\":\"q_1\",\"topic\":\"折射率\",\"question_type\":\"single_choice\",\"difficulty\":\"easy\"}]}");
        QuestionAttachments.Attachment file = new QuestionAttachments.Attachment(12L, "讲义.docx", "https://oss/x", null);
        when(attachments.extract(eq(List.of(file)), any(), any(), any())).thenReturn(
                new QuestionAttachments.Extracted("[附件文档]\n[文件:讲义.docx]\n折射率 n = c / v。", "- 讲义.docx (document)"));
        List<String> exploreUsers = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        scripted[0] = (request, out) -> {
            if (calls.incrementAndGet() == 1) {
                exploreUsers.add(ChatAgentLoop.textOf(last(request)));
                out.text("附件已读。");
            } else {
                out.text(questionJson("折射率", "甲"));
            }
        };

        service().run("key", custom("出折射率的题", "easy",
                new LinkedHashMap<>(Map.of(CourseQuestionType.SINGLE_CHOICE, 1)), 1,
                List.of(), List.of(file)), model, events::add, () -> false);

        assertThat(exploreUsers).hasSize(1);
        assertThat(exploreUsers.getFirst())
                .contains("[附件文档]").contains("折射率 n = c / v").contains("[教师要求]\n出折射率的题")
                .contains("## 附件\n- 讲义.docx (document)");
        List<String> types = events.stream().map(e -> String.valueOf(e.get("type"))).toList();
        assertThat(types).containsSubsequence("stage", "stage", "explore_text", "stage", "plan", "question", "done");
        assertThat(events.getFirst().get("phase")).isEqualTo("parsing");
    }

    @Test
    void 仿写模式跳过探索与规划_参考素材进逐题提示词() {
        QuestionAttachments.Attachment paper = new QuestionAttachments.Attachment(30L, "期中卷.pdf", "https://oss/p", "tok");
        when(mimicSource.templates(eq("key"), eq(paper), eq(5), any(), any(), any())).thenReturn(List.of(
                new QuestionPipelineService.QuizTemplate("q_1", "光在真空中的速度约为?", CourseQuestionType.SINGLE_CHOICE,
                        "easy", "mimic", "光在真空中的速度约为?A. 3×10^8 m/s B. 3×10^6 m/s", "A")));
        List<String> quizUsers = new ArrayList<>();
        scripted[0] = (request, out) -> {
            quizUsers.add(ChatAgentLoop.textOf(last(request)));
            out.text(questionJson("真空光速", "甲"));
        };

        service().run("key", new QuestionPipelineService.Job(QuestionPipelineService.Mode.MIMIC, 6L, "", "",
                Map.of(), 0, RetrievalTools.Mounts.NONE, List.of(), paper, 5), model, events::add, () -> false);

        assertThat(llm.textCalls).isEmpty();
        assertThat(quizUsers).hasSize(1);
        assertThat(quizUsers.getFirst())
                .contains("参考题目:\n光在真空中的速度约为?A. 3×10^8 m/s")
                .contains("参考答案:\nA")
                .contains("(无探索轨迹——仿写模式跳过了阶段 1");
        // heartbeat 是节流的保活事件,与阶段流无关,滤掉后精确比对
        List<String> types = events.stream().map(e -> String.valueOf(e.get("type")))
                .filter(type -> !"heartbeat".equals(type)).toList();
        assertThat(types).containsExactly("stage", "plan", "stage", "question", "done");
        assertThat(events.getFirst().get("phase")).isEqualTo("parsing");
    }

    @Test
    void 仿写模式未抽到可用题目时失败() {
        QuestionAttachments.Attachment paper = new QuestionAttachments.Attachment(30L, "期中卷.pdf", "https://oss/p", "tok");
        when(mimicSource.templates(anyString(), any(), anyInt(), any(), any(), any())).thenReturn(List.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service().run("key",
                new QuestionPipelineService.Job(QuestionPipelineService.Mode.MIMIC, 6L, "", "",
                        Map.of(), 0, RetrievalTools.Mounts.NONE, List.of(), paper, 10),
                model, events::add, () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有抽取到可仿写的题目");
    }
}
