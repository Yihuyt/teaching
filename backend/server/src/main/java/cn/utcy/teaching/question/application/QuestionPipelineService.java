package cn.utcy.teaching.question.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.ai.structured.JsonResponseParser;
import cn.utcy.teaching.ai.llm.LlmCalls;
import cn.utcy.teaching.ai.llm.LlmModels;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.shared.sse.SseHeartbeat;
import cn.utcy.teaching.shared.sse.SseSupport;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService.KnowledgeBaseRef;
import cn.utcy.teaching.retrieval.application.RetrievalTools;
import cn.utcy.teaching.question.application.QuestionAttachments.Attachment;
import cn.utcy.teaching.question.application.QuestionDraftNormalizer.DraftQuestion;
import cn.utcy.teaching.question.domain.CourseQuestion;
import cn.utcy.teaching.question.domain.CourseQuestionType;
import cn.utcy.teaching.question.infrastructure.CourseQuestionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * AI 出题管线(三阶段):
 *   1. 探索——智能体循环,模型自行决定是否/如何调用 rag 工具,工具结果经反思压缩后回填,
 *      全过程序列化为探索轨迹;
 *   2. 规划——一次调用给出每题蓝图(题型配比严格对齐请求,topic 互不重复);
 *   3. 逐题——每题一个小循环(rag 仍挂载),上下文 = 本题 template + 完整探索轨迹 + 规划 +
 *      已出题目;产出单题 JSON → 规整 → 检查 → 一次修复 → 带问题标记交付。
 * 没选知识库时不挂工具,探索照跑(纯思考)。附件文本以「[附件文档]」块拼进用户消息;
 * 试卷仿写(mimic)模式由 {@link ExamMimicSource} 抽出 template 直接进入逐题阶段,跳过探索与规划。
 * 鉴权、配比、密钥、知识库就绪与资料校验都在请求线程完成(4xx 不进流)。
 */
@Service
public class QuestionPipelineService {

    public static final int MAX_PER_TYPE = 10;
    public static final int MAX_TOTAL = 20;
    public static final int MAX_KNOWLEDGE_BASES = 5;
    public static final int DEFAULT_MIMIC_QUESTIONS = 10;
    private static final int EXISTING_TITLE_LIMIT = 200;

    static final ChatAgentLoop.Config EXPLORE_CONFIG = ChatAgentLoop.Config.of(8, 0)
            .withToolPolicy(Set.of(), 12, 60_000, Map.of());
    static final ChatAgentLoop.Config QUIZ_CONFIG = ChatAgentLoop.Config.of(5, 0)
            .withToolPolicy(Set.of(), 6, 60_000, Map.of());

    private static final Map<String, String> DIFFICULTY_LABELS = Map.of(
            "easy", "简单", "medium", "中等", "hard", "困难");

    private final LlmModels llmModels;
    private final LlmCalls llm;
    private final PromptLoader prompts;
    private final ModelConfig llmModel;
    private final CourseQuestionMapper questions;
    private final QuestionDraftNormalizer normalizer;
    private final KnowledgeBaseRetrievalService retrieval;
    private final RetrievalTools retrievalTools;
    private final QuestionAttachments attachments;
    private final ExamMimicSource mimicSource;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final CourseAiKeys aiKeys;
    private final ObjectMapper objectMapper;
    private final TaskExecutor sseExecutor;
    private final Executor toolExecutor;

    public QuestionPipelineService(LlmModels llmModels,
                                   LlmCalls llm,
                                   PromptLoader prompts,
                                   @Qualifier("questionLlmModel") ModelConfig llmModel,
                                   CourseQuestionMapper questions,
                                   QuestionDraftNormalizer normalizer,
                                   KnowledgeBaseRetrievalService retrieval,
                                   RetrievalTools retrievalTools,
                                   QuestionAttachments attachments,
                                   ExamMimicSource mimicSource,
                                   CourseAccess courseAccess,
                                   CurrentActor currentActor,
                                   CourseAiKeys aiKeys,
                                   ObjectMapper objectMapper,
                                   @Qualifier("sseTaskExecutor") TaskExecutor sseExecutor,
                                   @Qualifier("questionToolExecutor") Executor toolExecutor) {
        this.llmModels = llmModels;
        this.llm = llm;
        this.prompts = prompts;
        this.llmModel = llmModel;
        this.questions = questions;
        this.normalizer = normalizer;
        this.retrieval = retrieval;
        this.retrievalTools = retrievalTools;
        this.attachments = attachments;
        this.mimicSource = mimicSource;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.aiKeys = aiKeys;
        this.objectMapper = objectMapper;
        this.sseExecutor = sseExecutor;
        this.toolExecutor = toolExecutor;
    }

    /**
     * 一条 template:规划器产出的 source="custom";从试卷抽出的 source="mimic",
     * 带原题与参考答案供逐题阶段仿写。
     */
    public record QuizTemplate(String questionId, String topic, CourseQuestionType type, String difficulty,
                               String source, String referenceQuestion, String referenceAnswer) {
        static QuizTemplate custom(String questionId, String topic, CourseQuestionType type, String difficulty) {
            return new QuizTemplate(questionId, topic, type, difficulty, "custom", null, null);
        }

        boolean mimic() {
            return "mimic".equals(source);
        }
    }

    public enum Mode {
        CUSTOM, MIMIC
    }

    /**
     * 请求线程解析完毕、可交给 SSE 任务的一次出题作业。
     * custom:requirement / counts / difficulty 必填;mimic:examPaper / maxQuestions 必填。
     */
    record Job(Mode mode, long courseId, String requirement, String difficulty,
               Map<CourseQuestionType, Integer> counts, int total, RetrievalTools.Mounts mounts,
               List<Attachment> attachments, Attachment examPaper, int maxQuestions) {
    }

    /**
     * 生成入口(SSE)。事件:stage{phase} / progress{message} / explore_text{text} /
     * tool{name,phase,summary} / notice{message} / plan{analysis,templates} /
     * question{index,total,draft} / heartbeat / done{total} / error{message}。
     */
    public SseEmitter generateSse(long courseId, Mode mode, String requirement, List<Long> knowledgeBaseIds,
                                  List<Long> attachmentMaterialIds,
                                  Long examMaterialId, Integer maxQuestions, String difficulty,
                                  Map<CourseQuestionType, Integer> counts) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        List<Long> kbIds = knowledgeBaseIds == null ? List.of() : knowledgeBaseIds;
        if (kbIds.size() > MAX_KNOWLEDGE_BASES) {
            throw new BadRequestException("一次最多挂载 " + MAX_KNOWLEDGE_BASES + " 个知识库");
        }
        Job job = mode == Mode.MIMIC
                ? mimicJob(courseId, examMaterialId, maxQuestions)
                : customJob(courseId, requirement, attachmentMaterialIds, difficulty, counts);
        // 密钥与知识库就绪都在请求线程解析:未配置/未就绪立刻 4xx,不进 SSE
        String apiKey = aiKeys.llmKeyForCourse(courseId);
        List<KnowledgeBaseRef> knowledgeBases = kbIds.isEmpty() ? List.of()
                : retrieval.requireReady(courseId, kbIds);
        Job ready = new Job(job.mode(), courseId, job.requirement(), job.difficulty(), job.counts(), job.total(),
                new RetrievalTools.Mounts(knowledgeBases), job.attachments(), job.examPaper(),
                job.maxQuestions());
        StreamingChatModel model = llmModels.chatModel(apiKey, llmModel);
        return SseSupport.run(sseExecutor, objectMapper, sink -> run(apiKey, ready, model, sink::emit, sink::cancelled));
    }

    private Job customJob(long courseId, String requirement, List<Long> attachmentMaterialIds,
                          String difficulty, Map<CourseQuestionType, Integer> counts) {
        if (requirement == null || requirement.isBlank()) {
            throw new BadRequestException("出题要求不能为空");
        }
        Map<CourseQuestionType, Integer> plan = new LinkedHashMap<>();
        int total = 0;
        for (CourseQuestionType type : CourseQuestionType.values()) {
            int count = counts.getOrDefault(type, 0);
            if (count < 0 || count > MAX_PER_TYPE) {
                throw new BadRequestException(QuestionDraftNormalizer.TYPE_LABELS.get(type)
                        + "数量必须在 0~" + MAX_PER_TYPE + " 之间");
            }
            if (count > 0) {
                plan.put(type, count);
                total += count;
            }
        }
        if (total == 0) {
            throw new BadRequestException("请至少为一种题型设置数量");
        }
        if (total > MAX_TOTAL) {
            throw new BadRequestException("单次生成总数不能超过 " + MAX_TOTAL + " 道");
        }
        if (difficulty == null || !DIFFICULTY_LABELS.containsKey(difficulty)) {
            throw new BadRequestException("难度必须是 easy/medium/hard 之一");
        }
        List<Attachment> files = attachments.resolve(courseId,
                attachmentMaterialIds == null ? List.of() : attachmentMaterialIds);
        return new Job(Mode.CUSTOM, courseId, requirement.trim(), difficulty, plan, total, RetrievalTools.Mounts.NONE,
                files, null, 0);
    }

    private Job mimicJob(long courseId, Long examMaterialId, Integer maxQuestions) {
        if (examMaterialId == null) {
            throw new BadRequestException("试卷仿写需要上传一份试卷 PDF");
        }
        int max = maxQuestions == null ? DEFAULT_MIMIC_QUESTIONS : maxQuestions;
        if (max < 1 || max > MAX_TOTAL) {
            throw new BadRequestException("仿写题数必须在 1~" + MAX_TOTAL + " 之间");
        }
        Attachment paper = attachments.resolveOne(courseId, examMaterialId);
        if (!paper.pdf()) {
            throw new BadRequestException("试卷仿写仅支持 PDF 试卷");
        }
        return new Job(Mode.MIMIC, courseId, "", "", Map.of(), 0, RetrievalTools.Mounts.NONE, List.of(), paper, max);
    }

    /** 管线主流程(鉴权由调用方完成);model 可注入脚本化的假模型供测试驱动 */
    void run(String apiKey, Job job, StreamingChatModel model,
             Consumer<Map<String, Object>> emit, java.util.function.BooleanSupplier cancelled) {
        long courseId = job.courseId();
        RetrievalTools.Mounts mounts = job.mounts();
        Set<String> usedTitles = loadExistingTitles(courseId);
        String existingTitles = usedTitles.isEmpty()
                ? "" : String.join("\n", usedTitles.stream().map(t -> "- " + t).toList());
        QuestionTools tools = new QuestionTools(retrievalTools, llm, prompts);
        List<ToolSpecification> toolDefinitions = tools.definitions(mounts);
        RetrievalTools.SourceRegistry registry = new RetrievalTools.SourceRegistry();
        Consumer<String> notice = message -> emit.accept(Map.of("type", "notice", "message", message));
        Consumer<String> progress = message -> emit.accept(Map.of("type", "progress", "message", message));
        ChatAgentLoop loop = new ChatAgentLoop(objectMapper, toolExecutor, llm);
        SseHeartbeat heartbeat = new SseHeartbeat(emit);
        ExplorationTrace trace = new ExplorationTrace();

        String explorationTrace;
        String planAnalysis;
        List<QuizTemplate> templates;
        if (job.mode() == Mode.MIMIC) {
            // mimic:试卷 → template,跳过探索与规划;轨迹用空标记,让逐题提示词仍然成段
            emit.accept(Map.of("type", "stage", "phase", "parsing"));
            templates = mimicSource.templates(apiKey, job.examPaper(), job.maxQuestions(),
                    progress, notice, cancelled);
            if (cancelled.getAsBoolean()) {
                return;
            }
            if (templates.isEmpty()) {
                throw new IllegalStateException("试卷中没有抽取到可仿写的题目(平台仅支持单选 / 填空 / 判断)");
            }
            explorationTrace = "(无探索轨迹——仿写模式跳过了阶段 1;请依赖参考素材和 template 字段)";
            planAnalysis = "";
        } else {
            QuestionAttachments.Extracted extracted;
            if (job.attachments().isEmpty()) {
                extracted = new QuestionAttachments.Extracted("", QuestionAttachments.summary(List.of()));
            } else {
                emit.accept(Map.of("type", "stage", "phase", "parsing"));
                extracted = attachments.extract(job.attachments(), progress, notice, cancelled);
                if (cancelled.getAsBoolean()) {
                    return;
                }
            }
            String userMessage = extracted.documentsBlock().isEmpty()
                    ? job.requirement()
                    : extracted.documentsBlock() + "\n\n[教师要求]\n" + job.requirement();
            String perTypeCounts = String.join(", ", job.counts().entrySet().stream()
                    .map(e -> e.getKey().value() + "=" + e.getValue()).toList());

            // ---- 阶段 1:探索 ----
            emit.accept(Map.of("type", "stage", "phase", "exploring"));
            Map<String, Object> exploreVars = new HashMap<>();
            exploreVars.put("totalCount", job.total());
            exploreVars.put("perTypeCounts", perTypeCounts);
            exploreVars.put("difficultyLabel", DIFFICULTY_LABELS.get(job.difficulty()));
            exploreVars.put("requirement", userMessage);
            exploreVars.put("attachmentsSummary", extracted.summary());
            exploreVars.put("existingTitles", existingTitles);
            exploreVars.put("kbNote", QuestionTools.kbNote(mounts));
            exploreVars.put("toolList", QuestionTools.toolList(mounts));
            PromptLoader.Prompt explorePrompt = prompts.build("question/prompts", "explore", exploreVars);
            ChatAgentLoop.Outcome explored = loop.run(model, EXPLORE_CONFIG,
                    explorePrompt.system(), List.of(UserMessage.from(explorePrompt.user())), toolDefinitions,
                    tools.runner(apiKey, llmModel, courseId, mounts, registry, trace, notice),
                    loopEvents(emit, heartbeat, notice, delta -> {
                        trace.appendThought(delta);
                        emit.accept(Map.of("type", "explore_text", "text", delta));
                    }), cancelled);
            if (!explored.completed()) {
                return;
            }
            trace.finish(explored.finalText());

            // ---- 阶段 2:规划 ----
            emit.accept(Map.of("type", "stage", "phase", "planning"));
            explorationTrace = trace.render();
            Map<String, Object> planVars = new HashMap<>(exploreVars);
            planVars.put("explorationTrace", explorationTrace);
            PromptLoader.Prompt planPrompt = prompts.build("question/prompts", "plan", planVars);
            String planRaw = llm.chatText(apiKey, llmModel, List.of(
                    SystemMessage.from(planPrompt.system()), UserMessage.from(planPrompt.user())), false, null);
            JsonNode planJson = JsonResponseParser.parse(planRaw);
            templates = parsePlan(planJson, job.counts(), job.difficulty(), job.total(), notice);
            if (templates.isEmpty()) {
                throw new IllegalStateException("规划阶段没有产出任何题目蓝图,请调整出题要求后重试");
            }
            planAnalysis = planJson == null ? "" : planJson.path("analysis").asText("");
        }
        List<Map<String, Object>> templateViews = new ArrayList<>();
        for (QuizTemplate template : templates) {
            templateViews.add(Map.of("questionId", template.questionId(), "topic", template.topic(),
                    "type", template.type(), "difficulty", template.difficulty()));
        }
        emit.accept(Map.of("type", "plan", "analysis", planAnalysis, "templates", templateViews));
        String planSummary = String.join("\n", templates.stream()
                .map(t -> "- " + t.questionId() + ":" + t.topic() + "(" + t.type().value() + " / "
                        + t.difficulty() + ")").toList());

        // ---- 阶段 3:逐题 ----
        emit.accept(Map.of("type", "stage", "phase", "quizzing"));
        List<String> previous = new ArrayList<>();
        int delivered = 0;
        for (int i = 0; i < templates.size(); i++) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            QuizTemplate template = templates.get(i);
            DraftQuestion draft = quizOne(apiKey, courseId, template, i + 1, templates.size(),
                    explorationTrace, planSummary, previous, existingTitles, usedTitles, mounts, registry,
                    toolDefinitions, tools, trace, model, loop, emit, heartbeat, notice, cancelled);
            if (draft == null) {
                return;
            }
            delivered++;
            usedTitles.add(draft.title());
            previous.add(template.questionId() + ":" + draft.title() + " —— "
                    + QuestionDraftNormalizer.textOf(objectMapper.valueToTree(draft.stemMarkdown())));
            emit.accept(Map.of("type", "question", "index", i + 1, "total", templates.size(),
                    "draft", draft));
        }
        emit.accept(Map.of("type", "done", "total", delivered));
    }

    /** @return null = 任务被取消,调用方直接收尾 */
    private DraftQuestion quizOne(String apiKey, long courseId, QuizTemplate template, int number, int total,
                                  String explorationTrace, String planSummary, List<String> previous,
                                  String existingTitles, Set<String> usedTitles,
                                  RetrievalTools.Mounts mounts, RetrievalTools.SourceRegistry registry,
                                  List<ToolSpecification> toolDefinitions,
                                  QuestionTools tools, ExplorationTrace trace,
                                  StreamingChatModel model, ChatAgentLoop loop,
                                  Consumer<Map<String, Object>> emit, SseHeartbeat heartbeat,
                                  Consumer<String> notice, java.util.function.BooleanSupplier cancelled) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("questionNumber", number);
        vars.put("totalCount", total);
        vars.put("questionId", template.questionId());
        vars.put("topic", template.topic());
        vars.put("typeValue", template.type().value());
        vars.put("difficulty", template.difficulty());
        vars.put("typeRules", QuestionDraftNormalizer.typeRules(template.type()));
        vars.put("explorationTrace", explorationTrace);
        vars.put("planSummary", planSummary);
        vars.put("previousQuestions", previous.isEmpty() ? "(这是本轮第一题)" : String.join("\n", previous));
        vars.put("referenceBlock", referenceBlock(template));
        vars.put("existingTitles", existingTitles);
        vars.put("kbNote", QuestionTools.kbNote(mounts));
        vars.put("toolList", QuestionTools.toolList(mounts));
        PromptLoader.Prompt prompt = prompts.build("question/prompts", "quiz", vars);

        ChatAgentLoop.Outcome outcome = loop.run(model, QUIZ_CONFIG, prompt.system(),
                List.of(UserMessage.from(prompt.user())), toolDefinitions,
                tools.runner(apiKey, llmModel, courseId, mounts, registry, trace, notice),
                loopEvents(emit, heartbeat, notice, null), cancelled);
        if (!outcome.completed()) {
            return null;
        }

        ObjectNode question = normalizer.normalize(JsonResponseParser.parse(outcome.finalText()), template.type());
        List<String> issues = normalizer.collectIssues(question, template.type(), usedTitles);
        if (!issues.isEmpty()) {
            notice.accept("第 " + number + " 题载荷不合法;做一次格式修复。");
            ObjectNode repaired = repair(apiKey, template, question, issues);
            if (repaired != null) {
                question = repaired;
                issues = normalizer.collectIssues(question, template.type(), usedTitles);
                if (!issues.isEmpty()) {
                    notice.accept("第 " + number + " 题修复未能完全修正;使用尽力版本输出。");
                }
            }
        }
        return normalizer.toDraft(question, template.type(), template.topic(), issues);
    }

    /**
     * 循环事件 → 出题 SSE 协议:正文增量交给 onDelta(探索阶段进轨迹与 explore_text,逐题阶段不外发),
     * 工具起止转成 tool 事件(结束时带压缩结果的片段),notice 原样转发,思考增量只用来续心跳。
     */
    private ChatAgentLoop.Events loopEvents(Consumer<Map<String, Object>> emit, SseHeartbeat heartbeat,
                                            Consumer<String> notice, Consumer<String> onDelta) {
        return new ChatAgentLoop.Events() {
            @Override
            public void onRoundStart(String callId, String label) {
                heartbeat.tick();
            }

            @Override
            public void onReasoning(String callId, String delta) {
                heartbeat.tick();
            }

            @Override
            public void onContent(String callId, String delta) {
                if (onDelta != null) {
                    onDelta.accept(delta);
                }
            }

            @Override
            public void onRoundEnd(String callId, String role) {
            }

            @Override
            public void onToolCall(String callId, String toolCallId, String name, String argumentsJson) {
                emit.accept(Map.of("type", "tool", "name", name, "phase", "start", "summary", ""));
            }

            @Override
            public void onToolResult(String callId, String toolCallId, String name,
                                     ChatAgentLoop.ToolResult result) {
                emit.accept(Map.of("type", "tool", "name", name, "phase", "end",
                        "summary", RetrievalTools.snippet(result.content())));
            }

            @Override
            public void onNotice(String message) {
                notice.accept(message);
            }
        };
    }

    static String referenceBlock(QuizTemplate template) {
        if (!template.mimic()) {
            return "(无参考素材——这是从零生成的自定义题)";
        }
        String question = template.referenceQuestion() == null ? "" : template.referenceQuestion().strip();
        String answer = template.referenceAnswer() == null ? "" : template.referenceAnswer().strip();
        if (question.isEmpty() && answer.isEmpty()) {
            return "(无参考素材——这是从零生成的自定义题)";
        }
        List<String> parts = new ArrayList<>();
        if (!question.isEmpty()) {
            parts.add("参考题目:\n" + question);
        }
        if (!answer.isEmpty()) {
            parts.add("参考答案:\n" + answer);
        }
        return String.join("\n\n", parts);
    }

    /** 一次格式修复调用;解析不出返回 null */
    private ObjectNode repair(String apiKey, QuizTemplate template, ObjectNode question, List<String> issues) {
        ObjectNode invalidPayload = objectMapper.createObjectNode();
        ArrayNode items = invalidPayload.putArray("items");
        if (question != null) {
            items.add(question);
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("typeLabel", QuestionDraftNormalizer.TYPE_LABELS.get(template.type()));
        vars.put("typeValue", template.type().value());
        vars.put("count", 1);
        vars.put("difficultyLabel", DIFFICULTY_LABELS.getOrDefault(template.difficulty(), template.difficulty()));
        vars.put("typeRules", QuestionDraftNormalizer.typeRules(template.type()));
        vars.put("invalidPayload", invalidPayload.toPrettyString());
        vars.put("issues", String.join("\n", issues.stream().map(s -> "- " + s).toList()));
        PromptLoader.Prompt prompt = prompts.build("question/prompts", "repair", vars);
        String raw = llm.chatText(apiKey, llmModel, List.of(
                SystemMessage.from(prompt.system()), UserMessage.from(prompt.user())), false, null);
        return normalizer.normalize(JsonResponseParser.parse(raw), template.type());
    }

    /**
     * 解析规划:题型白名单、难度强制为用户指定、
     * 数量不符只发 notice 按实际继续;题型配比不符也 notice(逐题阶段仍按 template 题型出)。
     */
    List<QuizTemplate> parsePlan(JsonNode planJson, Map<CourseQuestionType, Integer> counts,
                                 String difficulty, int total, Consumer<String> notice) {
        List<QuizTemplate> templates = new ArrayList<>();
        JsonNode array = planJson == null ? null : planJson.path("templates");
        if (array != null && array.isArray()) {
            int index = 0;
            for (JsonNode node : array) {
                index++;
                String typeValue = node.path("question_type").asText("");
                CourseQuestionType type = null;
                try {
                    type = CourseQuestionType.fromValue(typeValue);
                } catch (IllegalArgumentException ignored) {
                    // 非白名单题型按跳过处理
                }
                if (type == null) {
                    notice.accept("规划中 q_" + index + " 的题型「" + typeValue + "」不在允许列表,已跳过。");
                    continue;
                }
                String topic = node.path("topic").asText("").strip();
                if (topic.isEmpty()) {
                    notice.accept("规划中 q_" + index + " 缺少 topic,已跳过。");
                    continue;
                }
                String id = node.path("question_id").asText("").strip();
                templates.add(QuizTemplate.custom(id.isEmpty() ? "q_" + index : id, topic, type, difficulty));
            }
        }
        if (templates.size() != total) {
            notice.accept("规划返回了 " + templates.size() + " 个 template(要求 " + total + " 个),按实际数量继续。");
        }
        Map<CourseQuestionType, Long> actual = new LinkedHashMap<>();
        templates.forEach(t -> actual.merge(t.type(), 1L, Long::sum));
        for (Map.Entry<CourseQuestionType, Integer> entry : counts.entrySet()) {
            if (actual.getOrDefault(entry.getKey(), 0L) != entry.getValue().longValue()) {
                notice.accept("规划的题型配比与要求不一致:" + QuestionDraftNormalizer.TYPE_LABELS.get(entry.getKey())
                        + " 要求 " + entry.getValue() + " 道,实际 " + actual.getOrDefault(entry.getKey(), 0L) + " 道。");
            }
        }
        return templates;
    }

    /** 库存标题采样(近更新优先):喂给模型做"不得重复考查"的依据 */
    private Set<String> loadExistingTitles(long courseId) {
        Page<CourseQuestion> page = questions.selectPage(
                Page.of(1, EXISTING_TITLE_LIMIT),
                new LambdaQueryWrapper<CourseQuestion>()
                        .select(CourseQuestion::getTitle)
                        .eq(CourseQuestion::getCourseId, courseId)
                        .orderByDesc(CourseQuestion::getUpdatedAt));
        Set<String> titles = new LinkedHashSet<>();
        page.getRecords().forEach(q -> titles.add(q.getTitle()));
        return titles;
    }

}
