package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.ai.agent.ChatAgentLoop.ToolResult;
import cn.utcy.teaching.ai.llm.LlmModels;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingProperties;
import cn.utcy.teaching.shared.sse.SseSupport;
import cn.utcy.teaching.blockcoding.application.ChatViews.MessageChanges;
import cn.utcy.teaching.blockcoding.application.ChatViews.QuestionView;
import cn.utcy.teaching.blockcoding.application.ChatViews.Quote;
import cn.utcy.teaching.blockcoding.application.ChatViews.ScriptTextView;
import cn.utcy.teaching.blockcoding.application.ChatViews.ScriptView;
import cn.utcy.teaching.blockcoding.application.agent.ChangeCommitter;
import cn.utcy.teaching.blockcoding.application.agent.Project;
import cn.utcy.teaching.blockcoding.application.agent.ScratchAgentPrompt;
import cn.utcy.teaching.blockcoding.application.agent.ScratchProgramAgent;
import cn.utcy.teaching.blockcoding.application.agent.Script;
import cn.utcy.teaching.blockcoding.application.agent.Workspace;
import cn.utcy.teaching.blockcoding.domain.AssistantMode;
import cn.utcy.teaching.blockcoding.domain.BlockCodingChatMessage;
import cn.utcy.teaching.blockcoding.domain.BlockCodingChatSession;
import cn.utcy.teaching.blockcoding.engine.SbToText;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingChatMessageMapper;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingChatSessionMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 一轮对话:用户发一条消息 → 智能体跑一轮 → 结果落库。修改模式(作品):跑完把改动经 {@link EditorBridge} 一次性写进编辑器;
 * 讲解模式:没有作品,只有回复。给用户的回复经 final_answer 边到边流出(content),工具之间的话只做过程提示(narration)。
 * 事件:content{text} / narration{text} / round{role} / tool{name,phase,error} / notice{message}(给用户看的提示)/
 * script{…}(本轮写进作品的一段)/ question{text,options} / browser_tool{callId,name,args}(要前端在编辑器里执行并回传)/
 * done{messageId,seq} / error{message}。
 */
@Service
public class ChatTurn {
    private static final Logger log = LoggerFactory.getLogger(ChatTurn.class);

    private final ChatSessions chatSessions;
    private final BlockCodingChatSessionMapper sessions;
    private final BlockCodingChatMessageMapper messages;
    private final MessageRecords records;
    private final CourseBlockCodingConfigService courseConfig;
    private final ScratchProgramAgent agent;
    private final ProjectStacks projectStacks;
    private final LlmModels llmModels;
    private final BlockCodingProperties properties;
    private final ObjectMapper objectMapper;
    private final TaskExecutor sseExecutor;
    private final CurrentActor currentActor;
    private final CourseAiKeys aiKeys;
    private final BrowserToolCalls browserCalls;
    /** 正在跑的会话:一段对话同时只跑一轮(消息序号、编辑器里的改动都以此为前提;单实例部署,内存即可) */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public ChatTurn(ChatSessions chatSessions, BlockCodingChatSessionMapper sessions, BlockCodingChatMessageMapper messages,
                    MessageRecords records, CourseBlockCodingConfigService courseConfig, ScratchProgramAgent agent,
                    SbToText sbToText, LlmModels llmModels, BlockCodingProperties properties,
                    ObjectMapper objectMapper, @Qualifier("sseTaskExecutor") TaskExecutor sseExecutor,
                    CurrentActor currentActor, CourseAiKeys aiKeys, BrowserToolCalls browserCalls) {
        this.chatSessions = chatSessions;
        this.sessions = sessions;
        this.messages = messages;
        this.records = records;
        this.courseConfig = courseConfig;
        this.agent = agent;
        this.projectStacks = new ProjectStacks(sbToText);
        this.llmModels = llmModels;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.sseExecutor = sseExecutor;
        this.currentActor = currentActor;
        this.aiKeys = aiKeys;
        this.browserCalls = browserCalls;
    }

    public ScriptTextView scriptText(String sprite, String xml) {
        Script script = projectStacks.fromXml(sprite, xml);
        if (script == null) {
            throw new BadRequestException("这段积木读不出来,换一段试试");
        }
        return new ScriptTextView(script.code(), script.blockCount());
    }

    public SseEmitter send(long sessionId, String content, HarvestPayload harvest, List<Quote> quotes, String requestedMode) {
        Actor actor = currentActor.require();
        BlockCodingChatSession session = chatSessions.requireOwned(sessionId);
        AssistantMode mode = AssistantMode.resolve(chatSessions.allowedModes(session, actor), requestedMode);
        HarvestPayload project = mode == AssistantMode.AGENT ? harvest : null;
        if (mode == AssistantMode.AGENT && project == null) {
            throw new BadRequestException("修改模式要带上作品快照");
        }
        List<ScratchAgentPrompt.Quoted> quoted = quoted(content, quotes);
        // 请求本身有问题(积木读不出来、没配密钥、上一轮还没结束)不留消息
        long courseId = chatSessions.courseOf(session);
        String apiKey = aiKeys.llmKeyForCourse(courseId);
        if (!running.add(sessionId)) {
            throw new BadRequestException("上一条消息还在处理中,等它结束再发");
        }
        try {
            return run(session, courseId, mode, project, quoted, content, apiKey);
        } catch (RuntimeException exception) {
            running.remove(sessionId);
            throw exception;
        }
    }

    private SseEmitter run(BlockCodingChatSession session, long courseId, AssistantMode mode, HarvestPayload project,
                           List<ScratchAgentPrompt.Quoted> quoted, String content, String apiKey) {
        long sessionId = session.getId();
        session.touch();
        sessions.updateById(session);
        List<BlockCodingChatMessage> prior = messages.selectBySession(sessionId);
        int userSeq = prior.isEmpty() ? 1 : prior.getLast().getSeq() + 1;
        messages.insert(BlockCodingChatMessage.of(sessionId, userSeq, "user", content,
                records.write(new MessageChanges(quoted.stream().map(ScriptView::quoted).toList(), List.of(), List.of(), null, false))));

        String guidance = courseConfig.activeTutorGuidance(courseId);
        // 不开思考:助手靠 tool_choice 强制模型收尾,百炼的思考模式不支持强制工具
        ModelConfig llmModel = new ModelConfig("blockcoding", courseConfig.modelForCourse(courseId), false,
                properties.temperature(), properties.topP(), properties.maxOutputTokens());
        List<ChatMessage> history = ChatHistory.of(records, prior);
        // 修改模式以作品快照为底;讲解模式没有作品(上一轮画的积木在历史里),只把之前回复里定义过的自定义积木带上
        Workspace workspace = project == null ? Workspace.CHAT : Workspace.PROJECT;
        Project workbench = project == null
                ? Project.empty().inheritProcedures(agent.proceduresIn(prior.stream()
                        .filter(m -> "assistant".equals(m.getRole())).map(BlockCodingChatMessage::getContent).toList()))
                : Project.fromHarvest(project, projectStacks.of(project));

        return SseSupport.run(sseExecutor, objectMapper, sink -> {
            try {
                turn(sink, session, mode, workspace, workbench, quoted, content, apiKey, llmModel, guidance, history, userSeq);
            } finally {
                running.remove(sessionId);
            }
        });
    }

    private void turn(SseSupport.EventSink sink, BlockCodingChatSession session, AssistantMode mode, Workspace workspace,
                      Project workbench, List<ScratchAgentPrompt.Quoted> quoted, String content, String apiKey,
                      ModelConfig llmModel, String guidance, List<ChatMessage> history, int userSeq) {
        long sessionId = session.getId();
        ScratchProgramAgent.Generation generation = agent.generate(llmModels.chatModel(apiKey, llmModel), workspace, workbench,
                quoted, content, guidance, history, new SseListener(sessionId, sink), sink::cancelled);
        if (sink.cancelled()) {
            return;
        }
        MessageChanges changes;
        if (mode == AssistantMode.AGENT) {
            ChangeCommitter.CommitReport report;
            try {
                report = ChangeCommitter.commit(generation.diff(), new EditorBridge(sink, browserCalls));
            } catch (ChangeCommitter.CommitFailed failed) {
                log.warn("积木助手 会话 {} 提交改动失败", sessionId, failed);
                report = failed.report();
                sink.emit(Map.of("type", "notice", "message", failed.getMessage() + ";已写入 " + report.scripts().size() + " 段脚本,其余没有动"));
            }
            changes = MessageChanges.of(report, QuestionView.of(generation.question()));
        } else {
            changes = new MessageChanges(List.of(), List.of(), List.of(), QuestionView.of(generation.question()), false);
        }
        for (ScriptView script : changes.scripts()) {
            sink.emit(Map.of("type", "script", "script", script));
        }
        if (generation.question() != null) {
            sink.emit(Map.of("type", "question", "text", generation.question().text(),
                    "options", generation.question().options()));
        }
        // 流式期间会话可能已被清空:不给已删会话留孤儿消息
        if (sessions.selectById(sessionId) == null) {
            throw new NotFoundException("会话不存在");
        }
        BlockCodingChatMessage assistant = BlockCodingChatMessage.of(sessionId, userSeq + 1, "assistant", generation.reply(),
                records.write(changes));
        messages.insert(assistant);
        sink.emit(Map.of("type", "done", "messageId", assistant.getId(), "seq", assistant.getSeq()));
    }

    public void completeBrowserTool(long sessionId, String callId, boolean ok, JsonNode result, String error) {
        chatSessions.requireOwned(sessionId);
        browserCalls.complete(callId, ok, result, error);
    }

    private List<ScratchAgentPrompt.Quoted> quoted(String content, List<Quote> quotes) {
        List<ScratchAgentPrompt.Quoted> quoted = new ArrayList<>();
        for (Quote quote : quotes == null ? List.<Quote>of() : quotes) {
            Script script = projectStacks.fromXml(quote.sprite(), quote.xml());
            if (script == null) {
                throw new BadRequestException("拖进来的积木读不出来,请去掉它再发");
            }
            if (!content.contains("【" + quote.label() + "】")) {
                throw new BadRequestException("正文里没有【" + quote.label() + "】这个标记,和积木对不上");
            }
            quoted.add(new ScratchAgentPrompt.Quoted(quote.label(), Script.preexisting(quoted.size() + 1,
                    script.sprite(), script.code(), script.xml(), script.blockCount(), script.definedProcedures(), quote.blockId())));
        }
        return quoted;
    }

    /** 智能体的过程事件 → SSE 与日志。模型在工具之间的自述一轮攒一段记日志:它为什么反复重写、为什么不收尾只能从这里看 */
    private static final class SseListener implements ScratchProgramAgent.Listener {
        private final long sessionId;
        private final SseSupport.EventSink sink;
        private final StringBuilder narration = new StringBuilder();

        SseListener(long sessionId, SseSupport.EventSink sink) {
            this.sessionId = sessionId;
            this.sink = sink;
        }

        @Override
        public void onContent(String delta) {
            sink.emit(Map.of("type", "content", "text", delta));
        }

        @Override
        public void onReplyReset() {
            sink.emit(Map.of("type", "reply_reset"));
        }

        @Override
        public void onNarration(String delta) {
            narration.append(delta);
            sink.emit(Map.of("type", "narration", "text", delta));
        }

        @Override
        public void onRoundEnd(String role) {
            if (!narration.isEmpty()) {
                log.info("积木助手 会话 {} 模型自述 {}", sessionId, Text.abbreviate(narration.toString().strip(), 600));
                narration.setLength(0);
            }
            sink.emit(Map.of("type", "round", "role", role));
        }

        @Override
        public void onTool(String name, String argumentsJson) {
            log.info("积木助手 会话 {} 调用 {} {}", sessionId, name, Text.abbreviate(argumentsJson, 4000));
            sink.emit(Map.of("type", "tool", "name", name, "phase", "start"));
        }

        @Override
        public void onToolResult(String name, ToolResult result) {
            log.info("积木助手 会话 {} {} {} {}", sessionId, name, result.isError() ? "失败" : "完成", Text.abbreviate(result.content(), 300));
            sink.emit(Map.of("type", "tool", "name", name, "phase", "end", "error", result.isError()));
        }

        @Override
        public void onNotice(String message) {
            log.info("积木助手 会话 {} 提示 {}", sessionId, message);
            sink.emit(Map.of("type", "notice", "message", message));
        }

        @Override
        public void onTrace(String message) {
            log.info("积木助手 会话 {} 循环 {}", sessionId, message);
        }
    }
}
