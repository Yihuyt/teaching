package cn.utcy.teaching.ai.agent;

import cn.utcy.teaching.ai.llm.AiUnavailableException;
import cn.utcy.teaching.ai.llm.FakeLlm;
import cn.utcy.teaching.ai.llm.ScriptedStreamingChatModel;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChatAgentLoopTest {

    private static final List<ToolSpecification> TOOLS = List.of(ToolSpecification.builder().name("rag")
            .parameters(JsonObjectSchema.builder().addStringProperty("query").build()).build());
    private static final List<ToolSpecification> TOOLS_WITH_DONE = List.of(TOOLS.getFirst(),
            ToolSpecification.builder().name("done").description("收尾").parameters(JsonObjectSchema.builder().build()).build());

    private final ObjectMapper om = new ObjectMapper();
    private final List<String> log = new ArrayList<>();

    private ChatAgentLoop.Events events() {
        return new ChatAgentLoop.Events() {
            @Override
            public void onRoundStart(String callId, String label) {
                log.add("round:start:" + label);
            }

            @Override
            public void onReasoning(String callId, String delta) {
                log.add("thinking:" + delta);
            }

            @Override
            public void onContent(String callId, String delta) {
                log.add("content:" + delta);
            }

            @Override
            public void onRoundEnd(String callId, String role) {
                log.add("round:end:" + role);
            }

            @Override
            public void onToolCall(String callId, String toolCallId, String name, String argumentsJson) {
                log.add("tool:call:" + name + ":" + argumentsJson);
            }

            @Override
            public void onToolResult(String callId, String toolCallId, String name, ChatAgentLoop.ToolResult result) {
                log.add("tool:result:" + name + ":" + result.content());
            }

            @Override
            public void onNotice(String message) {
                log.add("notice:" + message);
            }
        };
    }

    private ChatAgentLoop.Outcome run(ScriptedStreamingChatModel.Turn turn, ChatAgentLoop.ToolRunner runner,
                                      ChatAgentLoop.Config config) {
        return run(turn, runner, config, TOOLS);
    }

    private ChatAgentLoop.Outcome run(ScriptedStreamingChatModel.Turn turn, ChatAgentLoop.ToolRunner runner,
                                      ChatAgentLoop.Config config, List<ToolSpecification> tools) {
        ScriptedStreamingChatModel model = new ScriptedStreamingChatModel(turn);
        ChatAgentLoop loop = new ChatAgentLoop(om, Runnable::run, new FakeLlm(model));
        return loop.run(model, config, "系统", List.of(UserMessage.from("问题")), tools, runner, events(), () -> false);
    }

    private static ChatMessage last(List<ChatMessage> messages) {
        return messages.get(messages.size() - 1);
    }

    @Test
    void 纯文本轮即为正式回答_标为finish() {
        ChatAgentLoop.Outcome outcome = run((request, out) -> out.thinking("想一想").text("答案"),
                (id, name, args) -> ChatAgentLoop.ToolResult.of("x", List.of()), ChatAgentLoop.Config.of(8, 0));

        assertThat(outcome.finalText()).isEqualTo("答案");
        assertThat(outcome.completed()).isTrue();
        assertThat(outcome.rounds()).isEqualTo(1);
        assertThat(log).containsExactly("round:start:探索", "thinking:想一想", "content:答案", "round:end:finish");
    }

    @Test
    void 带工具的轮标为narration_结果原文回填_下一轮收尾() {
        AtomicInteger calls = new AtomicInteger();
        ChatAgentLoop.Outcome outcome = run((request, out) -> {
            if (calls.incrementAndGet() == 1) {
                out.text("我先查一下。").toolCall("call_1", "rag", "{\"query\":\"反射\",\"kb_name\":\"光学\"}");
                return;
            }
            assertThat(request.toolSpecifications()).extracting(ToolSpecification::name).containsExactly("rag");
            assertThat(last(request.messages())).isInstanceOfSatisfying(ToolExecutionResultMessage.class, result -> {
                assertThat(result.id()).isEqualTo("call_1");
                assertThat(result.text()).isEqualTo("[source-1] 反射角等于入射角");
            });
            out.text("反射角等于入射角[source-1]。");
        }, (id, name, args) -> ChatAgentLoop.ToolResult.of("[source-1] 反射角等于入射角",
                List.of(Map.of("ref", "source-1"))), ChatAgentLoop.Config.of(8, 0));

        assertThat(outcome.finalText()).isEqualTo("反射角等于入射角[source-1]。");
        assertThat(outcome.toolSteps()).isEqualTo(1);
        assertThat(outcome.sources()).hasSize(1);
        assertThat(log).containsSubsequence("content:我先查一下。", "round:end:narration",
                "tool:call:rag:{\"query\":\"反射\",\"kb_name\":\"光学\"}", "tool:result:rag:[source-1] 反射角等于入射角",
                "round:end:finish");
        assertThat(outcome.trace()).hasSize(1);
    }

    @Test
    void 只有思考没有回答_一次性nudge后继续() {
        AtomicInteger calls = new AtomicInteger();
        ChatAgentLoop.Outcome outcome = run((request, out) -> {
            if (calls.incrementAndGet() == 1) {
                out.text("<think>还在想</think>");
                return;
            }
            assertThat(ChatAgentLoop.textOf(last(request.messages()))).isEqualTo(ChatAgentLoop.FINISH_EMPTY_NUDGE);
            out.text("最终回答");
        }, (id, name, args) -> ChatAgentLoop.ToolResult.of("", List.of()), ChatAgentLoop.Config.of(8, 0));

        assertThat(outcome.finalText()).isEqualTo("最终回答");
        assertThat(log).contains("notice:" + ChatAgentLoop.NOTICE_EMPTY_FINISH_NUDGED);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void 第一轮强制调用工具_之后由模型自己决定() {
        List<ToolChoice> choices = new ArrayList<>();
        ChatAgentLoop.Outcome outcome = run((request, out) -> {
            choices.add(request.toolChoice());
            if (choices.size() == 1) {
                out.toolCall("call_1", "rag", "{\"query\":\"中文标点 全角括号\"}");
                return;
            }
            out.text("反射角等于入射角[source-1]。");
        }, (id, name, args) -> ChatAgentLoop.ToolResult.of("[source-1] 常见错误:中文标点当英文标点用", List.of()),
                ChatAgentLoop.Config.of(8, 0).withFirstRoundToolRequired());

        assertThat(choices).containsExactly(ToolChoice.REQUIRED, null);
        assertThat(outcome.finalText()).isEqualTo("反射角等于入射角[source-1]。");
        assertThat(outcome.toolSteps()).isEqualTo(1);
    }

    @Test
    void 只有工具能收尾_文字停下则追加提醒并强制下一轮调用工具() {
        List<ToolChoice> choices = new ArrayList<>();
        ChatAgentLoop.Outcome outcome = run((request, out) -> {
            choices.add(request.toolChoice());
            if (choices.size() == 1) {
                out.text("我觉得做完了。");
                return;
            }
            assertThat(ChatAgentLoop.textOf(last(request.messages()))).isEqualTo("做完了就调工具收尾");
            assertThat(ChatAgentLoop.textOf(request.messages().get(request.messages().size() - 2))).isEqualTo("我觉得做完了。");
            out.toolCall("call_1", "rag", "{\"query\":\"收尾\"}");
        }, (id, name, args) -> ChatAgentLoop.ToolResult.terminal("收尾了"),
                ChatAgentLoop.Config.of(8, 0).withToolFinish("做完了就调工具收尾", Set.of(), null));

        assertThat(choices).containsExactly(null, ToolChoice.REQUIRED);
        assertThat(outcome.terminatedBy()).isEqualTo("rag");
        assertThat(log).contains("notice:" + ChatAgentLoop.NOTICE_TEXT_STOP);
    }

    @Test
    void 只有工具能收尾_文字停下的下一轮只给收尾工具_被打回后全部工具还回来() {
        List<List<String>> offered = new ArrayList<>();
        ChatAgentLoop.Outcome outcome = run((request, out) -> {
            offered.add(request.toolSpecifications().stream().map(ToolSpecification::name).toList());
            switch (offered.size()) {
                case 1 -> out.text("我觉得做完了。");
                case 2 -> {
                    assertThat(request.toolChoice()).isEqualTo(ToolChoice.REQUIRED);
                    out.toolCall("call_1", "done", "{}");
                }
                case 3 -> out.toolCall("call_2", "rag", "{\"query\":\"补\"}");
                default -> out.toolCall("call_3", "done", "{}");
            }
        }, (id, name, args) -> name.equals("rag") ? ChatAgentLoop.ToolResult.of("r", List.of())
                : offered.size() == 2 ? ChatAgentLoop.ToolResult.error("还没好,先补上") : ChatAgentLoop.ToolResult.terminal("收尾了"),
                ChatAgentLoop.Config.of(8, 0).withToolFinish("继续", Set.of("done"), "现在收尾"), TOOLS_WITH_DONE);

        assertThat(offered).containsExactly(List.of("rag", "done"), List.of("done"), List.of("rag", "done"), List.of("rag", "done"));
        assertThat(outcome.terminatedBy()).isEqualTo("done");
    }

    @Test
    void 只有工具能收尾_轮数用尽则只给收尾工具并强制调用() {
        List<List<String>> offered = new ArrayList<>();
        List<ToolChoice> choices = new ArrayList<>();
        ChatAgentLoop.Outcome outcome = run((request, out) -> {
            offered.add(request.toolSpecifications().stream().map(ToolSpecification::name).toList());
            choices.add(request.toolChoice());
            if (request.toolSpecifications().size() == 1) {
                assertThat(ChatAgentLoop.textOf(last(request.messages()))).isEqualTo("现在收尾");
                out.toolCall("call_done", "done", "{}");
                return;
            }
            out.toolCall("call_" + offered.size(), "rag", "{\"query\":\"q" + offered.size() + "\"}");
        }, (id, name, args) -> name.equals("done") ? ChatAgentLoop.ToolResult.terminal("收尾了") : ChatAgentLoop.ToolResult.of("r", List.of()),
                ChatAgentLoop.Config.of(2, 0).withToolFinish("继续", Set.of("done"), "现在收尾"), TOOLS_WITH_DONE);

        assertThat(offered).containsExactly(List.of("rag", "done"), List.of("rag", "done"), List.of("done"));
        assertThat(choices).containsExactly(null, null, ToolChoice.REQUIRED);
        assertThat(outcome.terminatedBy()).isEqualTo("done");
        assertThat(log).contains("notice:" + ChatAgentLoop.NOTICE_BUDGET_EXHAUSTED);
    }

    @Test
    void 只有工具能收尾_工具结果要求收尾则下一轮只给收尾工具() {
        List<List<String>> offered = new ArrayList<>();
        ChatAgentLoop.Outcome outcome = run((request, out) -> {
            offered.add(request.toolSpecifications().stream().map(ToolSpecification::name).toList());
            if (request.toolSpecifications().size() == 1) {
                assertThat(request.toolChoice()).isEqualTo(ToolChoice.REQUIRED);
                out.toolCall("call_done", "done", "{}");
                return;
            }
            out.toolCall("call_1", "rag", "{\"query\":\"重复\"}");
        }, (id, name, args) -> name.equals("done") ? ChatAgentLoop.ToolResult.terminal("收尾了") : ChatAgentLoop.ToolResult.wrapUp("别再重复了"),
                ChatAgentLoop.Config.of(8, 0).withToolFinish("继续", Set.of("done"), "现在收尾"), TOOLS_WITH_DONE);

        assertThat(offered).containsExactly(List.of("rag", "done"), List.of("done"));
        assertThat(outcome.terminatedBy()).isEqualTo("done");
        assertThat(outcome.rounds()).isEqualTo(2);
        assertThat(log).contains("notice:" + ChatAgentLoop.NOTICE_WRAP_UP);
    }

    @Test
    void 只有工具能收尾_收尾阶段几轮都没收尾则禁工具要一段文字() {
        ChatAgentLoop.Outcome outcome = run((request, out) -> {
            if (request.toolSpecifications() == null || request.toolSpecifications().isEmpty()) {
                out.text("最后一段话");
                return;
            }
            out.toolCall("call_x", request.toolSpecifications().getFirst().name(), "{}");
        }, (id, name, args) -> ChatAgentLoop.ToolResult.error("还不行"),
                ChatAgentLoop.Config.of(1, 0).withToolFinish("继续", Set.of("done"), "现在收尾"), TOOLS_WITH_DONE);

        assertThat(outcome.finalText()).isEqualTo("最后一段话");
        assertThat(outcome.rounds()).isEqualTo(1 + ChatAgentLoop.WRAP_UP_ROUNDS + 1);
    }

    @Test
    void 预算用尽_追加收尾指令并禁工具再调一次() {
        List<Boolean> toolsPresent = new ArrayList<>();
        ChatAgentLoop.Outcome outcome = run((request, out) -> {
            boolean hasTools = request.toolSpecifications() != null && !request.toolSpecifications().isEmpty();
            toolsPresent.add(hasTools);
            if (!hasTools) {
                assertThat(ChatAgentLoop.textOf(last(request.messages()))).isEqualTo(ChatAgentLoop.FINISH_EXHAUSTED);
                out.text("基于已有材料的回答");
                return;
            }
            out.toolCall("call_" + toolsPresent.size(), "rag", "{\"query\":\"q" + toolsPresent.size() + "\"}");
        }, (id, name, args) -> ChatAgentLoop.ToolResult.of("r", List.of()), ChatAgentLoop.Config.of(2, 0));

        assertThat(toolsPresent).containsExactly(true, true, false);
        assertThat(outcome.finalText()).isEqualTo("基于已有材料的回答");
        assertThat(outcome.rounds()).isEqualTo(3);
        assertThat(log).contains("notice:" + ChatAgentLoop.NOTICE_BUDGET_EXHAUSTED);
    }

    @Test
    void 非首轮模型异常_强制收尾_首轮异常直接抛() {
        AtomicInteger calls = new AtomicInteger();
        ChatAgentLoop.Outcome outcome = run((request, out) -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                out.toolCall("c1", "rag", "{\"query\":\"a\"}");
            } else if (n == 2) {
                throw new IllegalStateException("boom");
            } else {
                out.text("兜住了");
            }
        }, (id, name, args) -> ChatAgentLoop.ToolResult.of("r", List.of()), ChatAgentLoop.Config.of(8, 0));
        assertThat(outcome.finalText()).isEqualTo("兜住了");
        assertThat(log).contains("notice:" + ChatAgentLoop.NOTICE_ERROR_FINISH);

        assertThatThrownBy(() -> run((request, out) -> {
            throw new IllegalStateException("first");
        }, (id, name, args) -> ChatAgentLoop.ToolResult.of("r", List.of()), ChatAgentLoop.Config.of(8, 0)))
                .isInstanceOf(AiUnavailableException.class).hasMessageContaining("first");
    }

    @Test
    void 同轮重复调用只执行一次_其余回填stub_工具异常回填提示() {
        AtomicInteger executed = new AtomicInteger();
        AtomicInteger calls = new AtomicInteger();
        ChatAgentLoop.Outcome outcome = run((request, out) -> {
            if (calls.incrementAndGet() == 1) {
                out.toolCall("c1", "rag", "{\"query\":\"a\",\"kb_name\":\"k\"}")
                        .toolCall("c2", "rag", "{\"kb_name\":\"k\",\"query\":\"a\"}")
                        .toolCall("c3", "rag", "{\"query\":\"boom\"}");
                return;
            }
            List<String> toolMessages = request.messages().stream()
                    .filter(m -> m instanceof ToolExecutionResultMessage)
                    .map(m -> ((ToolExecutionResultMessage) m).text()).toList();
            assertThat(toolMessages).hasSize(3);
            assertThat(toolMessages.get(1)).startsWith("(duplicate parallel tool_call — skipped.");
            assertThat(toolMessages.get(2)).isEqualTo("执行工具 rag 时发生未知错误。");
            out.text("完成");
        }, (id, name, args) -> {
            executed.incrementAndGet();
            if (args.contains("boom")) {
                throw new RuntimeException("x");
            }
            return ChatAgentLoop.ToolResult.of("ok", List.of());
        }, ChatAgentLoop.Config.of(8, 0));

        assertThat(executed.get()).isEqualTo(2);
        assertThat(outcome.finalText()).isEqualTo("完成");
    }

    @Test
    void 超出上下文窗口只裁剪较早的tool消息() {
        List<ChatMessage> messages = new ArrayList<>(List.of(
                UserMessage.from("问题"),
                ToolExecutionResultMessage.from("c1", "rag", "很长的旧结果".repeat(200)),
                ToolExecutionResultMessage.from("c2", "rag", "第二条")));
        ChatAgentLoop loop = new ChatAgentLoop(om, Runnable::run, new FakeLlm(new ScriptedStreamingChatModel((r, o) -> { })));
        loop.guardContextWindow("系统", messages, ChatAgentLoop.Config.of(8, 300), events());

        assertThat(ChatAgentLoop.textOf(messages.get(0))).isEqualTo("问题");
        assertThat(ChatAgentLoop.textOf(messages.get(1))).isEqualTo(ChatAgentLoop.TOOL_RESULT_SNIPPED);
        assertThat(ChatAgentLoop.textOf(messages.get(2))).isEqualTo("第二条");
        assertThat(log).containsExactly("notice:" + ChatAgentLoop.NOTICE_CONTEXT_GUARD);
    }
}
