package cn.utcy.teaching.tutor.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.learning.LearningEvent;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.learning.LearningEventType;
import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.ai.llm.LlmCalls;
import cn.utcy.teaching.ai.llm.LlmModels;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.shared.sse.SseHeartbeat;
import cn.utcy.teaching.shared.sse.SseSupport;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseRetrievalService.KnowledgeBaseRef;
import cn.utcy.teaching.retrieval.application.RetrievalTools;
import cn.utcy.teaching.tutor.infrastructure.TutorAssistantEntity;
import cn.utcy.teaching.tutor.infrastructure.TutorMessageEntity;
import cn.utcy.teaching.tutor.infrastructure.TutorProperties;
import cn.utcy.teaching.tutor.infrastructure.TutorSessionEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Service
public class TutorChatService {

    private static final Logger log = LoggerFactory.getLogger(TutorChatService.class);

    public static final int MAX_QUESTION_CHARS = 2000;
    static final int SEED_MAX_KBS = 3;
    static final int SEED_CHARS_PER_SOURCE = 4000;
    static final String SEED_HEADER = "[Knowledge Base Context]\n"
            + "以下是针对用户当前问题,从已挂载知识库预检索到的片段。\n"
            + "将它们作为有根据的上下文。它们可能不完整或部分无关;如果仍不够,用 rag 继续检索。";

    private final TutorSessionService sessions;
    private final TutorAssistantService assistants;
    private final RetrievalTools retrievalTools;
    private final LlmModels llmModels;
    private final LlmCalls llm;
    private final PromptLoader prompts;
    private final ModelConfig llmModel;
    private final TutorProperties properties;
    private final CourseAiKeys aiKeys;
    private final CurrentActor currentActor;
    private final LearningEventRecorder learningEvents;
    private final ObjectMapper objectMapper;
    private final TaskExecutor chatExecutor;
    private final Executor toolExecutor;

    public TutorChatService(TutorSessionService sessions, TutorAssistantService assistants,
                            RetrievalTools retrievalTools,
                            LlmModels llmModels, LlmCalls llm, PromptLoader prompts,
                            @Qualifier("tutorLlmModel") ModelConfig llmModel, TutorProperties properties,
                            CourseAiKeys aiKeys, CurrentActor currentActor,
                            LearningEventRecorder learningEvents, ObjectMapper objectMapper,
                            @Qualifier("tutorChatExecutor") TaskExecutor chatExecutor,
                            @Qualifier("tutorToolExecutor") Executor toolExecutor) {
        this.sessions = sessions;
        this.assistants = assistants;
        this.retrievalTools = retrievalTools;
        this.llmModels = llmModels;
        this.llm = llm;
        this.prompts = prompts;
        this.llmModel = llmModel;
        this.properties = properties;
        this.aiKeys = aiKeys;
        this.currentActor = currentActor;
        this.learningEvents = learningEvents;
        this.objectMapper = objectMapper;
        this.chatExecutor = chatExecutor;
        this.toolExecutor = toolExecutor;
    }

    record Turn(long courseId, long sessionId, long accountId, String question, TutorMounts mounts) {
    }

    public SseEmitter sendSse(long courseId, long sessionId, String rawQuestion) {
        Actor actor = currentActor.require();
        TutorSessionEntity session = sessions.requireOwn(courseId, sessionId, actor);
        TutorAssistantEntity assistant = assistants.requireInCourse(courseId, session.getAssistantId());
        TutorMounts mounts = assistants.mounts(assistant);
        String question = rawQuestion == null ? "" : rawQuestion.strip();
        if (question.isEmpty()) {
            throw new BadRequestException("问题不能为空");
        }
        if (question.length() > MAX_QUESTION_CHARS) {
            throw new BadRequestException("问题不能超过 " + MAX_QUESTION_CHARS + " 个字符");
        }
        String apiKey = aiKeys.llmKeyForCourse(courseId);
        Turn turn = new Turn(courseId, session.getId(), actor.userId(), question, mounts);
        StreamingChatModel model = llmModels.chatModel(apiKey, mounts.model());
        return SseSupport.run(chatExecutor, objectMapper, sink -> run(apiKey, turn, model, sink::emit, sink::cancelled));
    }

    void run(String apiKey, Turn turn, StreamingChatModel model, Consumer<Map<String, Object>> emit,
             BooleanSupplier cancelled) {
        TutorMounts mounts = turn.mounts();
        RetrievalTools.Mounts retrievalMounts = mounts.retrieval();
        RetrievalTools.SourceRegistry registry = new RetrievalTools.SourceRegistry();
        SseHeartbeat heartbeat = new SseHeartbeat(emit);

        List<TutorMessageEntity> history = sessions.listMessages(turn.sessionId());
        TutorMessageEntity userMessage = sessions.append(turn.sessionId(), "user", turn.question(), "[]", null);
        emit.accept(Map.of("type", "user_message", "id", userMessage.getId()));

        TutorContextBuilder contextBuilder = new TutorContextBuilder(llm, prompts, llmModel,
                properties.contextWindow());
        TutorContextBuilder.Result context = contextBuilder.build(apiKey,
                sessions.requireInCourse(turn.courseId(), turn.sessionId()), history, sessions::updateSummary);

        String seed = seedBlock(turn, registry, emit);
        List<ChatMessage> messages = new ArrayList<>(context.history());
        PromptLoader.Prompt prompt = prompts.build("tutor/prompts", "chat", Map.of(
                "assistantName", mounts.assistantName(),
                "mountNote", RetrievalTools.mountNote(retrievalMounts, NO_MOUNT_NOTE),
                "toolList", RetrievalTools.toolList(retrievalMounts),
                "instructions", mounts.instructions(),
                "userMessage", turn.question(),
                "seedBlock", seed));
        messages.add(UserMessage.from(prompt.user()));

        StringBuilder currentRound = new StringBuilder();
        Map<String, String> narrations = new LinkedHashMap<>();
        ChatAgentLoop loop = new ChatAgentLoop(objectMapper, toolExecutor, llm);
        // 挂了知识库就让模型第一轮先自己写查询词检索一次:学生的问法常和材料措辞不同,按原话预检索会落空;
        // 思考模式不支持强制工具,开了思考的助手只能靠提示词
        ChatAgentLoop.Config loopConfig = ChatAgentLoop.Config.of(mounts.maxRounds(), properties.contextWindow());
        if (!retrievalMounts.knowledgeBases().isEmpty() && !mounts.model().reasoning()) {
            loopConfig = loopConfig.withFirstRoundToolRequired();
        }
        ChatAgentLoop.Outcome outcome = loop.run(model, loopConfig,
                prompt.system(), messages, retrievalTools.definitions(retrievalMounts),
                (callId, name, argumentsJson) -> toToolResult(
                        retrievalTools.execute(turn.courseId(), retrievalMounts, registry, name, argumentsJson)),
                new ChatAgentLoop.Events() {
                    @Override
                    public void onRoundStart(String callId, String label) {
                        currentRound.setLength(0);
                        emit.accept(Map.of("type", "round", "callId", callId, "phase", "start", "label", label));
                    }

                    @Override
                    public void onReasoning(String callId, String delta) {
                        heartbeat.tick();
                        emit.accept(Map.of("type", "thinking", "callId", callId, "text", delta));
                    }

                    @Override
                    public void onContent(String callId, String delta) {
                        currentRound.append(delta);
                        emit.accept(Map.of("type", "content", "callId", callId, "text", delta));
                    }

                    @Override
                    public void onRoundEnd(String callId, String role) {
                        if ("narration".equals(role)) {
                            narrations.put(callId, currentRound.toString());
                        }
                        emit.accept(Map.of("type", "round", "callId", callId, "phase", "end", "role", role));
                    }

                    @Override
                    public void onToolCall(String callId, String toolCallId, String name, String argumentsJson) {
                        emit.accept(Map.of("type", "tool", "callId", callId, "toolCallId", toolCallId, "name", name,
                                "phase", "start", "args", argumentsJson));
                    }

                    @Override
                    public void onToolResult(String callId, String toolCallId, String name,
                                             ChatAgentLoop.ToolResult result) {
                        Map<String, Object> event = new HashMap<>();
                        event.put("type", "tool");
                        event.put("callId", callId);
                        event.put("toolCallId", toolCallId);
                        event.put("name", name);
                        event.put("phase", "end");
                        event.put("error", result.isError());
                        event.put("summary", RetrievalTools.snippet(result.content()));
                        event.put("sources", result.sources());
                        emit.accept(event);
                    }

                    @Override
                    public void onNotice(String message) {
                        emit.accept(Map.of("type", "notice", "message", message));
                    }
                }, cancelled);

        if (!outcome.completed()) {
            return;
        }
        List<Map<String, Object>> sources = registry.all();
        TutorMessageEntity assistant = sessions.append(turn.sessionId(), "assistant", outcome.finalText(),
                toJson(sources), toJson(trace(outcome, narrations)));
        learningEvents.record(new LearningEvent(turn.courseId(), turn.accountId(),
                LearningEventType.TUTOR_QUESTION_ASKED, turn.sessionId(), Map.of()));
        emit.accept(Map.of("type", "done", "assistantMessageId", assistant.getId(), "sources", sources));

        if (history.isEmpty()) {
            String title = new TutorTitleService(llm, prompts, llmModel)
                    .generate(apiKey, turn.question(), outcome.finalText());
            sessions.updateTitle(turn.sessionId(), title);
            emit.accept(Map.of("type", "title", "title", title));
        }
    }

    private Map<String, Object> trace(ChatAgentLoop.Outcome outcome, Map<String, String> narrations) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("rounds", outcome.rounds());
        trace.put("toolSteps", outcome.toolSteps());
        trace.put("narrations", narrations.values().stream().filter(text -> !text.isBlank()).toList());
        trace.put("tools", outcome.trace());
        return trace;
    }

    String seedBlock(Turn turn, RetrievalTools.SourceRegistry registry, Consumer<Map<String, Object>> emit) {
        TutorMounts mounts = turn.mounts();
        RetrievalTools.Mounts retrievalMounts = mounts.retrieval();
        List<CompletableFuture<Map.Entry<String, RetrievalTools.Outcome>>> futures = new ArrayList<>();
        for (KnowledgeBaseRef kb : mounts.knowledgeBases().stream().limit(SEED_MAX_KBS).toList()) {
            futures.add(CompletableFuture.supplyAsync(() -> Map.entry(kb.name(),
                    retrievalTools.rag(turn.courseId(), retrievalMounts, registry, turn.question(), kb.name())), toolExecutor));
        }
        List<String> sections = new ArrayList<>();
        List<Map<String, Object>> seedSources = new ArrayList<>();
        for (CompletableFuture<Map.Entry<String, RetrievalTools.Outcome>> future : futures) {
            Map.Entry<String, RetrievalTools.Outcome> entry;
            try {
                entry = future.join();
            } catch (RuntimeException exception) {
                log.warn("预检索失败,跳过该来源:{}", exception.getMessage());
                continue;
            }
            RetrievalTools.Outcome result = entry.getValue();
            if (result.isError() || result.sources().isEmpty()) {
                continue;
            }
            String text = result.content();
            if (text.length() > SEED_CHARS_PER_SOURCE) {
                text = text.substring(0, SEED_CHARS_PER_SOURCE).stripTrailing() + "\n...[truncated]";
            }
            sections.add("## " + entry.getKey() + "\n" + text);
            seedSources.addAll(result.sources());
        }
        if (sections.isEmpty()) {
            return "";
        }
        emit.accept(Map.of("type", "seed_sources", "sources", seedSources));
        return SEED_HEADER + "\n\n" + String.join("\n\n", sections);
    }

    static final String NO_MOUNT_NOTE = "本助手未挂载知识库,仅凭通用知识作答,并明确告知用户这一点。";

    static ChatAgentLoop.ToolResult toToolResult(RetrievalTools.Outcome outcome) {
        return outcome.isError() ? ChatAgentLoop.ToolResult.error(outcome.content())
                : ChatAgentLoop.ToolResult.of(outcome.content(), outcome.sources());
    }


    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

}
