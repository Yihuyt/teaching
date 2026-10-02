package cn.utcy.teaching.ai.agent;

import cn.utcy.teaching.ai.llm.LlmCalls;
import cn.utcy.teaching.ai.llm.ThinkingTags;
import cn.utcy.teaching.ai.llm.TokenEstimator;

import cn.utcy.teaching.shared.error.DomainException;
import cn.utcy.teaching.shared.util.Text;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialToolCall;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 对话型智能体循环:一个用户回合 = 一个循环,对话消息列表只增不减。
 * 每轮模型可调用工具(一轮内并行,上限 {@link Config#maxParallelToolCalls()}),工具结果原文作为 tool 消息回填;
 * **某轮没有工具调用即为正式回答**(finish),带工具调用的轮的正文是过程叙述(narration),只进轨迹不进答案。
 * <p>
 * 护栏:空回答一次性 nudge、轮次预算用尽后禁工具强制收尾、非首轮模型异常强制收尾、重复并行调用回填 stub、
 * 超窗口裁剪较早的 tool 消息、工具异常/空结果以提示文本回填继续。
 * <p>
 * 持久化工作台会话在此之上追加的机制:
 * <ul>
 *   <li>写者工具标 sequential:一批调用里只要有一个写者,整批按序串行(并行写者会互相覆盖,且紧邻的读要看到已提交态);</li>
 *   <li>每次工具调用有硬时间预算(按工具可覆盖),超时转为结构化错误结果回喂模型,循环不死;</li>
 *   <li>工具结果可要求终止本次运行(ask_user):问题已发出,答案是下一条用户消息;</li>
 *   <li>运行级工具调用总上限,超限强制收尾;</li>
 *   <li>轮间注入转向消息(用户在运行中追加的消息);每条追加到对话的消息经 {@link Events#onMessage} 外送,供持久化转录。</li>
 * </ul>
 */
public final class ChatAgentLoop {

    public static final int DEFAULT_MAX_ROUNDS = 8;
    public static final int MAX_PARALLEL_TOOL_CALLS = 8;
    public static final double CONTEXT_WINDOW_GUARD_RATIO = 0.9;

    static final String LABEL_EXPLORING = "探索";
    static final String LABEL_FINAL_RESPONSE = "生成回答";
    static final String FINISH_EXHAUSTED =
            "循环轮次预算已用尽,仍有缺口未补齐。现在停止调用工具,基于已有材料作答,并简短说明仍不确定的部分。";
    static final String FINISH_EMPTY_NUDGE =
            "你上一轮只输出了内部推理——既没有调用工具,也没有写出面向用户的回答。\n"
                    + "现在继续:要么调用工具执行你计划好的步骤,要么直接写出最终回答。";
    static final String NOTICE_EMPTY_FINISH_NUDGED = "模型这一轮只有内部推理,已提示其继续执行。";
    static final String NOTICE_TEXT_STOP = "模型只回了文字就停下,下一轮已强制它调用工具。";
    public static final String NOTICE_WRAP_UP = "工具要求收尾,接下来只给收尾工具。";
    /** 收尾阶段最多几轮:收尾工具可能打回一次(如收尾检查),再给不了更多 */
    static final int WRAP_UP_ROUNDS = 3;
    public static final String NOTICE_BUDGET_EXHAUSTED = "探索轮次预算已用尽,将基于已收集的材料作答。";
    public static final String NOTICE_TOOL_BUDGET_EXHAUSTED = "本次运行的工具调用次数已达上限,将基于已完成的工作收尾。";
    static final String NOTICE_ERROR_FINISH = "某一步执行失败,将基于已收集的材料作答。";
    static final String NOTICE_EMPTY_FINAL = "未能从模型输出中得到有效回答。请重试或缩小问题范围。";
    static final String NOTICE_CONTEXT_GUARD = "已裁剪较旧的工具结果,以确保本轮在模型上下文窗口内。";
    static final String TOOL_RESULT_SNIPPED =
            "[较早的工具结果已裁剪以保持在上下文窗口内;如果仍需要该内容,请再次调用同一工具]";
    static final String EMPTY_TOOL_RESULT = "工具执行完成,但没有返回文本内容。";

    /**
     * @param sequentialTools      写者工具名:批中含任一即整批串行
     * @param maxToolCalls         运行级工具调用总上限(≤0 不限)
     * @param toolTimeoutMs        单次工具调用默认时间预算(≤0 不限)
     * @param toolTimeoutOverrides 按工具名覆盖的时间预算
     * @param toolFinishReminder   非空 = 只有工具能收尾:模型只回文字就停下时,把这句话作为用户消息追加,下一轮用 tool_choice
     *                             强制它调用工具(百炼思考模式不支持强制,调用方要保证模型不开思考);空 = 文字轮就是正式回答
     * @param finishTools          收尾工具名(如 final_answer / ask_user):轮数或工具次数用完、或某个工具结果要求收尾时,
     *                             进入收尾阶段——只给这些工具且必须调用,最多 {@link #WRAP_UP_ROUNDS} 轮;空 = 用尽时禁工具要一段文字
     * @param wrapUpReminder       进入收尾阶段时追加的用户消息
     * @param firstRoundToolRequired 第一轮用 tool_choice 强制模型调用工具(有工具时):模型自己写查询词检索一次再作答,
     *                               而不是看它心情;百炼思考模式不支持强制,调用方要保证模型不开思考
     */
    public record Config(int maxRounds, int maxParallelToolCalls, int contextWindowTokens, double guardRatio,
                         Set<String> sequentialTools, int maxToolCalls, long toolTimeoutMs,
                         Map<String, Long> toolTimeoutOverrides, String toolFinishReminder,
                         Set<String> finishTools, String wrapUpReminder, boolean firstRoundToolRequired) {

        public static Config of(int maxRounds, int contextWindowTokens) {
            return new Config(Math.max(1, maxRounds), MAX_PARALLEL_TOOL_CALLS, contextWindowTokens,
                    CONTEXT_WINDOW_GUARD_RATIO, Set.of(), 0, 0, Map.of(), null, Set.of(), null, false);
        }

        public Config withToolPolicy(Set<String> sequential, int toolCallCap, long timeoutMs,
                                     Map<String, Long> overrides) {
            return new Config(maxRounds, maxParallelToolCalls, contextWindowTokens, guardRatio,
                    Set.copyOf(sequential), toolCallCap, timeoutMs, Map.copyOf(overrides), toolFinishReminder,
                    finishTools, wrapUpReminder, firstRoundToolRequired);
        }

        public Config withToolFinish(String textStopReminder, Set<String> finishToolNames, String wrapUp) {
            return new Config(maxRounds, maxParallelToolCalls, contextWindowTokens, guardRatio,
                    sequentialTools, maxToolCalls, toolTimeoutMs, toolTimeoutOverrides, textStopReminder,
                    Set.copyOf(finishToolNames), wrapUp, firstRoundToolRequired);
        }

        public Config withFirstRoundToolRequired() {
            return new Config(maxRounds, maxParallelToolCalls, contextWindowTokens, guardRatio,
                    sequentialTools, maxToolCalls, toolTimeoutMs, toolTimeoutOverrides, toolFinishReminder,
                    finishTools, wrapUpReminder, true);
        }

        long timeoutFor(String toolName) {
            Long override = toolTimeoutOverrides.get(toolName);
            return override != null ? override : toolTimeoutMs;
        }
    }

    /**
     * 工具结果:回填给模型的正文 + 给前端/落库的来源元数据;terminate 为真表示本次运行到此为止
     * (问题已交给用户,答案是下一条用户消息)。
     */
    public record ToolResult(String content, List<Map<String, Object>> sources, boolean isError,
                             boolean terminate, boolean wrapUp) {
        public ToolResult(String content, List<Map<String, Object>> sources, boolean isError) {
            this(content, sources, isError, false, false);
        }

        public ToolResult(String content, List<Map<String, Object>> sources, boolean isError, boolean terminate) {
            this(content, sources, isError, terminate, false);
        }

        public static ToolResult of(String content, List<Map<String, Object>> sources) {
            return new ToolResult(content, sources == null ? List.of() : sources, false, false, false);
        }

        public static ToolResult error(String content) {
            return new ToolResult(content, List.of(), true, false, false);
        }

        public static ToolResult terminal(String content) {
            return new ToolResult(content, List.of(), false, true, false);
        }

        /** 工具认定这轮该收尾了(如模型反复原样重交):下一轮起只给收尾工具且必须调用 */
        public static ToolResult wrapUp(String content) {
            return new ToolResult(content, List.of(), false, false, true);
        }
    }

    public interface ToolRunner {
        ToolResult run(String callId, String name, String argumentsJson);
    }

    public interface Events {
        void onRoundStart(String callId, String label);

        void onReasoning(String callId, String delta);

        void onContent(String callId, String delta);

        /** role = narration(本轮带工具调用,正文是过程叙述)| finish(本轮即正式回答) */
        void onRoundEnd(String callId, String role);

        /** 工具调用参数的流式增量(name 已知后才回调);想边到边显示某个工具参数的实现者用 */
        default void onToolCallDelta(String callId, String name, String argumentsFragment) {
        }

        void onToolCall(String callId, String toolCallId, String name, String argumentsJson);

        void onToolResult(String callId, String toolCallId, String name, ToolResult result);

        void onNotice(String message);

        /** 每条被追加到对话的消息(assistant / tool / 系统追加的 user),供持久化转录;默认忽略 */
        default void onMessage(ChatMessage message) {
        }
    }

    /**
     * @param completed    false = 被取消(未产生正式回答)
     * @param terminatedBy 由工具终止运行时的工具名(如 ask_user);正常收尾为 null
     */
    public record Outcome(String finalText, boolean completed, int rounds, int toolSteps,
                          List<Map<String, Object>> sources, ArrayNode trace, String terminatedBy) {
    }

    private record RoundResult(String text, List<ToolExecutionRequest> toolCalls) {
    }

    private final ObjectMapper om;
    private final Executor toolExecutor;
    private final LlmCalls llm;
    private String lastRoundCallId;

    public ChatAgentLoop(ObjectMapper om, Executor toolExecutor, LlmCalls llm) {
        this.om = om;
        this.toolExecutor = toolExecutor;
        this.llm = llm;
    }

    public Outcome run(StreamingChatModel model, Config loopConfig, String system, List<ChatMessage> initial,
                       List<ToolSpecification> tools, ToolRunner runner, Events events, BooleanSupplier cancelled) {
        return run(model, loopConfig, system, initial, tools, runner, events, cancelled, null);
    }

    /**
     * @param system 系统提示:每次请求前置,不进对话消息列表(调用方每轮重组,不进转录)
     * @param steer  轮间转向消息源(可空):每轮模型调用前取一次,非空即按序追加进对话
     */
    public Outcome run(StreamingChatModel model, Config loopConfig, String system, List<ChatMessage> initial,
                       List<ToolSpecification> tools, ToolRunner runner, Events events, BooleanSupplier cancelled,
                       Supplier<List<ChatMessage>> steer) {
        List<ChatMessage> messages = new ArrayList<>(initial);
        ArrayNode trace = om.createArrayNode();
        List<Map<String, Object>> sources = new ArrayList<>();
        int rounds = 0;
        int toolSteps = 0;
        int toolCallsUsed = 0;
        boolean nudgedEmptyFinish = false;
        List<ToolSpecification> activeTools = tools == null || tools.isEmpty() ? null : tools;
        // 只对紧接着的一次调用生效:文字停下之后的那一轮;收尾阶段每轮都强制
        ToolChoice toolChoice = null;
        List<ToolSpecification> finishTools = activeTools == null ? List.of()
                : activeTools.stream().filter(spec -> loopConfig.finishTools().contains(spec.name())).toList();
        boolean wrappingUp = false;
        int roundBudget = loopConfig.maxRounds();
        // 文字停下后的那一轮只给收尾工具;那一轮过后还原
        boolean restoreToolsAfterRound = false;

        for (int round = 0; ; round++) {
            if (round >= roundBudget) {
                if (finishTools.isEmpty() || wrappingUp) {
                    return forcedFinish(model, loopConfig, system, messages, events, rounds, toolSteps, sources,
                            trace, NOTICE_BUDGET_EXHAUSTED);
                }
                // 轮数用完但有收尾工具:不丢掉已做的,给它几轮只能收尾
                events.onNotice(NOTICE_BUDGET_EXHAUSTED);
                append(messages, UserMessage.from(loopConfig.wrapUpReminder()), events);
                activeTools = finishTools;
                wrappingUp = true;
                roundBudget = round + WRAP_UP_ROUNDS;
            }
            if (wrappingUp || (round == 0 && loopConfig.firstRoundToolRequired() && activeTools != null)) {
                toolChoice = ToolChoice.REQUIRED;
            }
            if (cancelled.getAsBoolean()) {
                return new Outcome("", false, rounds, toolSteps, sources, trace, null);
            }
            if (steer != null) {
                List<ChatMessage> incoming = steer.get();
                if (incoming != null) {
                    for (ChatMessage message : incoming) {
                        messages.add(message);
                        events.onMessage(message);
                    }
                }
            }
            RoundResult result;
            try {
                result = callModel(model, loopConfig, system, messages, activeTools, toolChoice, LABEL_EXPLORING, events);
            } catch (RuntimeException exception) {
                if (rounds == 0) {
                    throw exception;
                }
                return forcedFinish(model, loopConfig, system, messages, events, rounds, toolSteps,
                        sources, trace, NOTICE_ERROR_FINISH);
            }
            rounds++;
            toolChoice = null;
            if (restoreToolsAfterRound) {
                activeTools = tools == null || tools.isEmpty() ? null : tools;
                restoreToolsAfterRound = false;
            }
            if (result.toolCalls().isEmpty()) {
                String finalText = ThinkingTags.strip(result.text());
                if (finalText.isEmpty() && !nudgedEmptyFinish) {
                    nudgedEmptyFinish = true;
                    events.onNotice(NOTICE_EMPTY_FINISH_NUDGED);
                    if (!result.text().isEmpty()) {
                        append(messages, AiMessage.from(result.text()), events);
                    }
                    append(messages, UserMessage.from(FINISH_EMPTY_NUDGE), events);
                    continue;
                }
                if (loopConfig.toolFinishReminder() != null && activeTools != null) {
                    // 只有工具能收尾:模型停下 = 它认为说完了,这段文字只是过程叙述。追加提醒,下一轮只给收尾工具且必须调用,
                    // 免得它被迫调工具时随手改作品;收尾工具打回(收尾检查)后再把全部工具还给它
                    events.onNotice(NOTICE_TEXT_STOP);
                    if (!result.text().isEmpty()) {
                        append(messages, AiMessage.from(result.text()), events);
                    }
                    append(messages, UserMessage.from(loopConfig.toolFinishReminder()), events);
                    toolChoice = ToolChoice.REQUIRED;
                    if (!finishTools.isEmpty() && !wrappingUp) {
                        activeTools = finishTools;
                        restoreToolsAfterRound = true;
                    }
                    continue;
                }
                append(messages, AiMessage.from(result.text()), events);
                return finalize(finalText, events, rounds, toolSteps, sources, trace, true, null);
            }

            append(messages, AiMessage.from(result.text(), result.toolCalls()), events);
            String roundCallId = lastRoundCallId;

            List<ToolExecutionRequest> calls = result.toolCalls();
            if (calls.size() > loopConfig.maxParallelToolCalls()) {
                events.onNotice("模型请求了 " + calls.size() + " 个工具,单轮最多并行执行 "
                        + loopConfig.maxParallelToolCalls() + " 个,已截断。");
                calls = calls.subList(0, loopConfig.maxParallelToolCalls());
            }
            Map<Integer, Integer> duplicateOf = detectDuplicates(calls);
            boolean sequential = calls.stream().anyMatch(call -> loopConfig.sequentialTools().contains(call.name()));
            List<ToolResult> results = sequential
                    ? runSequentially(runner, calls, duplicateOf, loopConfig, events, roundCallId)
                    : runInParallel(runner, calls, duplicateOf, loopConfig, events, roundCallId);
            String terminatedBy = null;
            for (int i = 0; i < calls.size(); i++) {
                ToolExecutionRequest call = calls.get(i);
                ToolResult toolResult = results.get(i);
                String content = toolResult.content() == null || toolResult.content().isBlank()
                        ? EMPTY_TOOL_RESULT : toolResult.content();
                if (!toolResult.isError()) {
                    sources.addAll(toolResult.sources());
                }
                events.onToolResult(roundCallId, call.id(), call.name(), toolResult);
                ObjectNode entry = trace.addObject();
                entry.put("round", rounds);
                entry.put("tool", call.name());
                entry.put("arguments", Text.abbreviate(call.arguments(), 2_000));
                entry.put("result", Text.abbreviate(content, 4_000));
                entry.put("error", toolResult.isError());
                append(messages, ToolExecutionResultMessage.from(call.id(), call.name(), content), events);
                if (toolResult.terminate() && terminatedBy == null) {
                    terminatedBy = call.name();
                }
            }
            toolSteps++;
            toolCallsUsed += calls.size();
            // 被截断的调用也要有 tool 消息,否则协议的 call/result 配对断裂
            for (int i = calls.size(); i < result.toolCalls().size(); i++) {
                ToolExecutionRequest skipped = result.toolCalls().get(i);
                append(messages, ToolExecutionResultMessage.from(skipped.id(), skipped.name(),
                        "(skipped: exceeded the per-round parallel tool call limit)"), events);
            }
            if (terminatedBy != null) {
                return finalize("", events, rounds, toolSteps, sources, trace, true, terminatedBy);
            }
            boolean toolBudgetExhausted = loopConfig.maxToolCalls() > 0 && toolCallsUsed >= loopConfig.maxToolCalls();
            boolean wrapUpRequested = results.stream().anyMatch(ToolResult::wrapUp);
            if (!wrappingUp && !finishTools.isEmpty() && (toolBudgetExhausted || wrapUpRequested)) {
                events.onNotice(toolBudgetExhausted ? NOTICE_TOOL_BUDGET_EXHAUSTED : NOTICE_WRAP_UP);
                append(messages, UserMessage.from(loopConfig.wrapUpReminder()), events);
                activeTools = finishTools;
                wrappingUp = true;
                roundBudget = round + 1 + WRAP_UP_ROUNDS;
            } else if (toolBudgetExhausted && !wrappingUp) {
                return forcedFinish(model, loopConfig, system, messages, events, rounds, toolSteps,
                        sources, trace, NOTICE_TOOL_BUDGET_EXHAUSTED);
            }
        }
    }

    private static void append(List<ChatMessage> messages, ChatMessage message, Events events) {
        messages.add(message);
        events.onMessage(message);
    }

    private List<ToolResult> runInParallel(ToolRunner runner, List<ToolExecutionRequest> calls,
                                           Map<Integer, Integer> duplicateOf, Config loopConfig, Events events,
                                           String roundCallId) {
        List<CompletableFuture<ToolResult>> futures = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolExecutionRequest call = calls.get(i);
            events.onToolCall(roundCallId, call.id(), call.name(), call.arguments());
            if (duplicateOf.containsKey(i)) {
                futures.add(CompletableFuture.completedFuture(duplicateStub(calls.get(duplicateOf.get(i)))));
                continue;
            }
            futures.add(CompletableFuture.supplyAsync(() -> executeTool(runner, call), toolExecutor));
        }
        List<ToolResult> results = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            results.add(await(futures.get(i), calls.get(i), loopConfig));
        }
        return results;
    }

    private List<ToolResult> runSequentially(ToolRunner runner, List<ToolExecutionRequest> calls,
                                             Map<Integer, Integer> duplicateOf, Config loopConfig, Events events,
                                             String roundCallId) {
        List<ToolResult> results = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolExecutionRequest call = calls.get(i);
            events.onToolCall(roundCallId, call.id(), call.name(), call.arguments());
            if (duplicateOf.containsKey(i)) {
                results.add(duplicateStub(calls.get(duplicateOf.get(i))));
                continue;
            }
            CompletableFuture<ToolResult> future =
                    CompletableFuture.supplyAsync(() -> executeTool(runner, call), toolExecutor);
            results.add(await(future, call, loopConfig));
        }
        return results;
    }

    private static ToolResult duplicateStub(ToolExecutionRequest primary) {
        return ToolResult.error(
                "(duplicate parallel tool_call — skipped. The identical call with id='" + primary.id()
                        + "' already ran in this batch; see its result. Do NOT emit two identical "
                        + "tool_calls in one assistant message — parallel calls must differ in arguments.)");
    }

    /** 等待工具结果:超过时间预算即放弃等待(在途任务自行结束,其结果不再采纳),回填结构化错误 */
    private static ToolResult await(CompletableFuture<ToolResult> future, ToolExecutionRequest call, Config loopConfig) {
        long timeout = loopConfig.timeoutFor(call.name());
        try {
            return timeout > 0 ? future.get(timeout, TimeUnit.MILLISECONDS) : future.join();
        } catch (TimeoutException exception) {
            return ToolResult.error("工具 " + call.name() + " 超过 " + (timeout / 1000)
                    + " 秒的执行预算,已放弃等待,本次调用未完成。可重试该调用,或不依赖其结果继续。");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolResult.error("等待工具 " + call.name() + " 时被中断。");
        } catch (ExecutionException exception) {
            return ToolResult.error("执行工具 " + call.name() + " 时发生未知错误。");
        }
    }

    private ToolResult executeTool(ToolRunner runner, ToolExecutionRequest call) {
        try {
            ToolResult result = runner.run(call.id(), call.name(), call.arguments());
            return result == null ? ToolResult.of("", List.of()) : result;
        } catch (DomainException exception) {
            // 业务异常的文案本就面向用户(如「检索服务暂时不可用」),原样回填让模型能据此行动
            return ToolResult.error("执行工具 " + call.name() + " 失败:" + exception.getMessage());
        } catch (RuntimeException exception) {
            return ToolResult.error("执行工具 " + call.name() + " 时发生未知错误。");
        }
    }

    private Outcome forcedFinish(StreamingChatModel model, Config loopConfig, String system,
                                 List<ChatMessage> messages, Events events, int rounds, int toolSteps,
                                 List<Map<String, Object>> sources, ArrayNode trace, String notice) {
        events.onNotice(notice);
        append(messages, UserMessage.from(FINISH_EXHAUSTED), events);
        RoundResult result;
        try {
            result = callModel(model, loopConfig, system, messages, null, null, LABEL_FINAL_RESPONSE, events);
        } catch (RuntimeException exception) {
            return finalize("", events, rounds, toolSteps, sources, trace, true, null);
        }
        append(messages, AiMessage.from(result.text()), events);
        return finalize(ThinkingTags.strip(result.text()), events, rounds + 1, toolSteps, sources, trace, true,
                null);
    }

    private Outcome finalize(String finalText, Events events, int rounds, int toolSteps,
                             List<Map<String, Object>> sources, ArrayNode trace, boolean completed,
                             String terminatedBy) {
        if (finalText.isEmpty() && terminatedBy == null) {
            events.onNotice(NOTICE_EMPTY_FINAL);
            finalText = NOTICE_EMPTY_FINAL;
        }
        return new Outcome(finalText, completed, rounds, toolSteps, sources, trace, terminatedBy);
    }

    private RoundResult callModel(StreamingChatModel model, Config loopConfig, String system,
                                  List<ChatMessage> messages, List<ToolSpecification> tools, ToolChoice toolChoice,
                                  String label, Events events) {
        guardContextWindow(system, messages, loopConfig, events);
        String callId = "chat-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        lastRoundCallId = callId;
        events.onRoundStart(callId, label);
        List<ChatMessage> request = new ArrayList<>(messages.size() + 1);
        request.add(SystemMessage.from(system));
        request.addAll(messages);
        ChatRequest.Builder builder = ChatRequest.builder().messages(request);
        if (tools != null) {
            builder.toolSpecifications(tools);
        }
        if (toolChoice != null) {
            builder.toolChoice(toolChoice);
        }
        ChatResponse response = llm.chat(model, builder.build(), new LlmCalls.Listener() {
            @Override
            public void onText(String delta) {
                events.onContent(callId, delta);
            }

            @Override
            public void onThinking(String delta) {
                events.onReasoning(callId, delta);
            }

            @Override
            public void onToolCallDelta(PartialToolCall call) {
                if (call.name() != null && call.partialArguments() != null) {
                    events.onToolCallDelta(callId, call.name(), call.partialArguments());
                }
            }
        });
        AiMessage message = response.aiMessage();
        List<ToolExecutionRequest> toolCalls = new ArrayList<>();
        int index = 0;
        for (ToolExecutionRequest call : message.toolExecutionRequests()) {
            if (call.name() == null || call.name().isBlank()) {
                continue;
            }
            // 百炼拒绝空 id 的工具调用;空参数按 {} 回填
            String id = call.id() == null || call.id().isBlank() ? "call_" + index : call.id();
            toolCalls.add(ToolExecutionRequest.builder().id(id).name(call.name()).arguments(ensureJson(call.arguments())).build());
            index++;
        }
        String text = message.text() == null ? "" : message.text();
        events.onRoundEnd(callId, toolCalls.isEmpty() ? "finish" : "narration");
        return new RoundResult(text, toolCalls);
    }

    /** 同名同参(JSON 规范化后)的并行调用视为重复,映射到首个同样调用的下标 */
    Map<Integer, Integer> detectDuplicates(List<ToolExecutionRequest> calls) {
        Map<String, Integer> first = new LinkedHashMap<>();
        Map<Integer, Integer> duplicateOf = new LinkedHashMap<>();
        for (int i = 0; i < calls.size(); i++) {
            ToolExecutionRequest call = calls.get(i);
            String key = call.name() + " " + canonicalJson(call.arguments());
            Integer primary = first.putIfAbsent(key, i);
            if (primary != null) {
                duplicateOf.put(i, primary);
            }
        }
        return duplicateOf;
    }

    private String canonicalJson(String arguments) {
        try {
            Object tree = om.readValue(arguments, Object.class);
            return om.copy().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                    .writeValueAsString(tree);
        } catch (Exception exception) {
            return arguments == null ? "" : arguments;
        }
    }

    void guardContextWindow(String system, List<ChatMessage> messages, Config loopConfig, Events events) {
        int window = loopConfig.contextWindowTokens();
        if (window <= 0) {
            return;
        }
        int budget = (int) (window * loopConfig.guardRatio());
        if (estimateTokens(system, messages) <= budget) {
            return;
        }
        boolean snipped = false;
        for (int i = 0; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof ToolExecutionResultMessage tool) || TOOL_RESULT_SNIPPED.equals(tool.text())) {
                continue;
            }
            messages.set(i, ToolExecutionResultMessage.from(tool.id(), tool.toolName(), TOOL_RESULT_SNIPPED));
            snipped = true;
            if (estimateTokens(system, messages) <= budget) {
                break;
            }
        }
        if (snipped) {
            events.onNotice(NOTICE_CONTEXT_GUARD);
        }
    }

    static int estimateTokens(String system, List<ChatMessage> messages) {
        int total = TokenEstimator.count(system);
        for (ChatMessage message : messages) {
            total += TokenEstimator.count(textOf(message));
            if (message instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest call : ai.toolExecutionRequests()) {
                    total += TokenEstimator.count(call.arguments());
                }
            }
        }
        return total;
    }

    public static String textOf(ChatMessage message) {
        return switch (message) {
            case UserMessage user -> user.contents().stream()
                    .filter(dev.langchain4j.data.message.TextContent.class::isInstance)
                    .map(content -> ((dev.langchain4j.data.message.TextContent) content).text())
                    .collect(java.util.stream.Collectors.joining("\n"));
            case AiMessage ai -> ai.text() == null ? "" : ai.text();
            case ToolExecutionResultMessage tool -> tool.text() == null ? "" : tool.text();
            case SystemMessage sys -> sys.text();
            default -> "";
        };
    }

    private String ensureJson(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return "{}";
        }
        try {
            om.readTree(arguments);
            return arguments;
        } catch (Exception e) {
            return "{}";
        }
    }
}
