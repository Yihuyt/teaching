package cn.utcy.teaching.blockcoding.application.agent;

import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Procedure;
import cn.utcy.teaching.ai.agent.ChatAgentLoop.ToolResult;
import cn.utcy.teaching.ai.llm.LlmCalls;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * 积木助手智能体:一次请求 = 一次 {@link ChatAgentLoop} 运行。模型读技能、逐段 write_script 写进登记簿,编译错误作为工具结果回喂,
 * 产物是本轮对作品的改动清单(由调用方一次性落进编辑器;讲解模式没有作品,产物就是回复)。
 * 给用户的回复只经 final_answer 工具(终止本轮),模型在工具调用之间说的话是过程叙述,用户看不到;final_answer 的 text 边到边抠出来流给前端。
 * 只有 final_answer / ask_user 算收尾;模型只输出文字就停下的视为它认为说完了,循环下一轮只给收尾工具并强制调用。
 */
public final class ScratchProgramAgent {
    static final int MAX_ROUNDS = 30;
    static final int MAX_TOOL_CALLS = 60;
    static final long TOOL_TIMEOUT_MS = 30_000;
    static final String TEXT_STOP_REMINDER =
            "你只回了文字就停下了,这段话用户看不到。现在调用 final_answer 把要告诉用户的话写进它的 text;还没做完的如实说明做到哪了。";
    static final String USER_NOTICE_BUDGET = "助手这轮的步数用完了,按目前做到的收尾。";

    public record Generation(String reply, ProjectDiff diff, Question question, Project project, int rounds, int toolSteps,
                             boolean completed) {
    }

    public interface Listener {
        /** 给用户的回复的增量(来自 final_answer 工具的 text) */
        default void onContent(String delta) {
        }

        /** 上一次 final_answer 被打回,模型重新回复:之前流出的正文作废,从头再来 */
        default void onReplyReset() {
        }

        /** 模型在工具调用之间说的话的增量(过程叙述,只做状态提示) */
        default void onNarration(String delta) {
        }

        default void onRoundEnd(String role) {
        }

        /** 循环内部的进展(强制调工具、进入收尾阶段):只值得记日志,用户不需要看 */
        default void onTrace(String message) {
        }

        default void onTool(String name, String argumentsJson) {
        }

        default void onToolResult(String name, ToolResult result) {
        }

        default void onNotice(String message) {
        }
    }

    private final ScratchTools tools;
    private final ScratchAgentPrompt prompt;
    private final ObjectMapper objectMapper;
    private final Executor toolExecutor;
    private final LlmCalls llm;
    private final int contextWindowTokens;

    public ScratchProgramAgent(ScratchTools tools, ScratchAgentPrompt prompt, ObjectMapper objectMapper,
                               Executor toolExecutor, LlmCalls llm, int contextWindowTokens) {
        this.tools = tools;
        this.prompt = prompt;
        this.objectMapper = objectMapper;
        this.toolExecutor = toolExecutor;
        this.llm = llm;
        this.contextWindowTokens = contextWindowTokens;
    }

    public List<Procedure> proceduresIn(List<String> replies) {
        return tools.proceduresIn(replies);
    }

    public Generation generate(StreamingChatModel model, Workspace workspace, Project project,
                               List<ScratchAgentPrompt.Quoted> quoted, String request, String teacherGuidance,
                               List<ChatMessage> history, Listener listener, BooleanSupplier cancelled) {
        String system = prompt.system(workspace);
        List<ChatMessage> initial = new ArrayList<>(history == null ? List.of() : history);
        initial.add(UserMessage.from(prompt.user(workspace, project, request, quoted)));
        List<ToolSpecification> definitions = tools.definitions(workspace, ScratchAgentPrompt.guidance(teacherGuidance));

        // 只有 final_answer / ask_user 算收尾:模型只回文字就停下,下一轮只给收尾工具且强制调用(所以这个模型不能开思考模式)
        ChatAgentLoop.Config loopConfig = ChatAgentLoop.Config.of(MAX_ROUNDS, contextWindowTokens)
                .withToolPolicy(ScratchTools.WRITERS, MAX_TOOL_CALLS, TOOL_TIMEOUT_MS, Map.of())
                .withToolFinish(TEXT_STOP_REMINDER, ScratchTools.FINISHERS, workspace.wrapUpReminder());
        ChatAgentLoop loop = new ChatAgentLoop(objectMapper, toolExecutor, llm);
        ChatAgentLoop.ToolRunner runner = (callId, name, arguments) -> tools.execute(workspace, project, name, arguments);

        ChatAgentLoop.Outcome outcome = loop.run(model, loopConfig, system, initial, definitions, runner, new Events(listener, project), cancelled);
        if (project.question() != null) {
            return new Generation(project.question().text(), project.diff(), project.question(), project, outcome.rounds(),
                    outcome.toolSteps(), true);
        }
        String reply = project.finalAnswer();
        if (reply == null) {
            reply = outcome.finalText();
            if (outcome.completed()) {
                listener.onTrace("收尾阶段仍没有调用 final_answer,已把最后一段话当作回复");
                listener.onContent(reply);
            }
        }
        return new Generation(reply, project.diff(), null, project, outcome.rounds(), outcome.toolSteps(), outcome.completed());
    }

    private static final class Events implements ChatAgentLoop.Events {
        private final Listener listener;
        private final Project project;
        /** 每轮一个:final_answer 被打回后模型下一轮再交,正文要重新从头流 */
        private FinalAnswerTextStream finalAnswerText = new FinalAnswerTextStream();
        private boolean replyStreamed;
        private boolean replyResetThisRound;

        Events(Listener listener, Project project) {
            this.listener = listener;
            this.project = project;
        }

        @Override
        public void onRoundStart(String callId, String label) {
            finalAnswerText = new FinalAnswerTextStream();
            replyResetThisRound = false;
        }

        @Override
        public void onReasoning(String callId, String delta) {
        }

        @Override
        public void onContent(String callId, String delta) {
            listener.onNarration(delta);
        }

        @Override
        public void onRoundEnd(String callId, String role) {
            listener.onRoundEnd(role);
        }

        @Override
        public void onToolCallDelta(String callId, String name, String argumentsFragment) {
            if (ScratchTools.FINAL_ANSWER.equals(name)) {
                String delta = finalAnswerText.feed(argumentsFragment);
                if (!delta.isEmpty()) {
                    if (replyStreamed && !replyResetThisRound) {
                        listener.onReplyReset();
                    }
                    replyResetThisRound = true;
                    replyStreamed = true;
                    listener.onContent(delta);
                }
            }
        }

        @Override
        public void onToolCall(String callId, String toolCallId, String name, String argumentsJson) {
            listener.onTool(name, argumentsJson);
        }

        @Override
        public void onToolResult(String callId, String toolCallId, String name, ToolResult result) {
            listener.onToolResult(name, result);
        }

        @Override
        public void onNotice(String message) {
            if (ChatAgentLoop.NOTICE_BUDGET_EXHAUSTED.equals(message) || ChatAgentLoop.NOTICE_TOOL_BUDGET_EXHAUSTED.equals(message)
                    || ChatAgentLoop.NOTICE_WRAP_UP.equals(message)) {
                // 收尾阶段没有步数再改了:final_answer 照单全收,检查出的问题附在回复里,免得模型为过检查在最后几步乱删
                project.enterWrapUp();
            }
            if (ChatAgentLoop.NOTICE_BUDGET_EXHAUSTED.equals(message) || ChatAgentLoop.NOTICE_TOOL_BUDGET_EXHAUSTED.equals(message)) {
                listener.onNotice(USER_NOTICE_BUDGET);
            }
            listener.onTrace(message);
        }

        @Override
        public void onMessage(ChatMessage message) {
        }
    }
}
