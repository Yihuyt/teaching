package cn.utcy.teaching.tutor.application;

import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.ai.llm.LlmCalls;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.ai.llm.ThinkingTags;
import cn.utcy.teaching.ai.llm.TokenEstimator;
import cn.utcy.teaching.tutor.infrastructure.TutorMessageEntity;
import cn.utcy.teaching.tutor.infrastructure.TutorSessionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 对话历史装配:
 * 历史按 token 预算带入(窗口 × 0.35);超预算时把较早消息折进滚动摘要(预算 × 0.40),
 * 摘要与水位 summary_up_to_msg_id 存在会话上;摘要以 system 消息「[对话摘要]」进对话;
 * 摘要失败只降级本回合(保留旧摘要 + 全部未摘要消息,水位不前进,下回合重试);最后硬裁剪到预算内。
 */
final class TutorContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(TutorContextBuilder.class);

    static final double HISTORY_BUDGET_RATIO = 0.35;
    static final double SUMMARY_TARGET_RATIO = 0.40;
    static final double TRUNCATION_GUARD_RATIO = 0.95;
    static final String SUMMARY_HEADER = "[对话摘要]";

    interface SummaryStore {
        void update(long sessionId, String summary, long upToMsgId);
    }

    record Result(List<ChatMessage> history, String summary, int tokenCount, int budget) {
    }

    private final LlmCalls llm;
    private final PromptLoader prompts;
    private final ModelConfig model;
    private final int contextWindow;

    TutorContextBuilder(LlmCalls llm, PromptLoader prompts, ModelConfig model, int contextWindow) {
        this.llm = llm;
        this.prompts = prompts;
        this.model = model;
        this.contextWindow = contextWindow;
    }

    int historyBudget() {
        return Math.max(256, (int) (contextWindow * HISTORY_BUDGET_RATIO));
    }

    int summaryBudget(int budget) {
        return Math.max(96, (int) (budget * SUMMARY_TARGET_RATIO));
    }

    int recentBudget(int budget) {
        return Math.max(128, budget - summaryBudget(budget));
    }

    int rebuildSourceBudget() {
        return Math.max(1024, contextWindow / 2);
    }

    /**
     * @param messages 会话全部历史消息(不含本回合的新问题),按 id 升序
     */
    Result build(String apiKey, TutorSessionEntity session, List<TutorMessageEntity> messages, SummaryStore store) {
        int budget = historyBudget();
        String storedSummary = session.getCompressedSummary() == null ? "" : session.getCompressedSummary().strip();
        long storedWatermark = session.getSummaryUpToMsgId() == null ? 0 : session.getSummaryUpToMsgId();
        // 水位指向的消息已不存在(被删)→ 摘要作废,从头重建
        boolean watermarkValid = storedWatermark > 0
                && messages.stream().anyMatch(m -> m.getId() == storedWatermark);
        if (storedWatermark > 0 && !watermarkValid) {
            storedSummary = "";
        }
        final long upTo = watermarkValid ? storedWatermark : 0;
        final long watermark = upTo;
        List<TutorMessageEntity> unsummarized = messages.stream().filter(m -> m.getId() > watermark).toList();
        List<ChatMessage> current = history(storedSummary, unsummarized);
        if (tokens(current) <= budget) {
            return new Result(current, storedSummary, tokens(current), budget);
        }

        int recentBudget = recentBudget(budget);
        List<TutorMessageEntity> recent = selectRecent(unsummarized, recentBudget);
        List<TutorMessageEntity> prefix = messages.subList(0, messages.size() - recent.size());
        List<TutorMessageEntity> olderUnsummarized = unsummarized.subList(0, unsummarized.size() - recent.size());
        String prefixTranscript = transcript(prefix);
        boolean rebuildFromRaw = !prefixTranscript.isEmpty()
                && TokenEstimator.count(prefixTranscript) <= rebuildSourceBudget();
        List<String> parts = new ArrayList<>();
        if (rebuildFromRaw) {
            parts.add("Conversation history to summarize:\n" + prefixTranscript);
        } else {
            if (!storedSummary.isEmpty()) {
                parts.add("Existing summary:\n" + storedSummary);
            }
            String olderTranscript = transcript(olderUnsummarized);
            if (!olderTranscript.isEmpty()) {
                parts.add("Older turns to fold in:\n" + olderTranscript);
            }
        }
        if (parts.isEmpty() && !recent.isEmpty()) {
            parts.add(transcript(recent));
        }
        int summaryBudget = summaryBudget(budget);
        String newSummary = null;
        try {
            newSummary = summarize(apiKey, String.join("\n\n", parts), summaryBudget);
        } catch (RuntimeException exception) {
            log.warn("会话 {} 历史摘要失败,本回合带原文继续:{}", session.getId(), exception.getMessage());
        }

        List<ChatMessage> finalHistory;
        String finalSummary = storedSummary;
        if (newSummary != null && !newSummary.isBlank()) {
            long newWatermark = prefix.isEmpty() ? upTo : Math.max(upTo, prefix.get(prefix.size() - 1).getId());
            store.update(session.getId(), newSummary, newWatermark);
            finalSummary = newSummary;
            finalHistory = history(newSummary, recent);
        } else {
            finalHistory = current;
        }
        int summaryPrefix = !finalHistory.isEmpty() && finalHistory.get(0) instanceof SystemMessage ? 1 : 0;
        while (finalHistory.size() > summaryPrefix + 1 && tokens(finalHistory) > budget) {
            finalHistory.remove(summaryPrefix);
        }
        return new Result(finalHistory, finalSummary, tokens(finalHistory), budget);
    }

    private String summarize(String apiKey, String sourceText, int summaryBudget) {
        int target = Math.max(96, (int) (summaryBudget * 0.8));
        PromptLoader.Prompt prompt = prompts.build("tutor/prompts", "summary",
                Map.of("targetTokens", target, "sourceText", sourceText));
        ModelConfig summarizer = new ModelConfig(model.id() + "-summary", model.providerModel(),
                false, model.temperature(), model.topP(), summaryBudget);
        String summary = ThinkingTags.strip(llm.chatText(apiKey, summarizer, List.of(
                SystemMessage.from(prompt.system()), UserMessage.from(prompt.user())), false, null));
        if (TokenEstimator.count(summary) >= (int) (summaryBudget * TRUNCATION_GUARD_RATIO)) {
            summary = trimIncompleteTail(summary);
        }
        return summary.strip();
    }

    static List<TutorMessageEntity> selectRecent(List<TutorMessageEntity> messages, int recentBudget) {
        List<TutorMessageEntity> selected = new ArrayList<>();
        int total = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            int t = TokenEstimator.count(messages.get(i).getContent());
            if (!selected.isEmpty() && total + t > recentBudget) {
                break;
            }
            selected.add(0, messages.get(i));
            total += t;
        }
        return selected;
    }

    static List<ChatMessage> history(String summary, List<TutorMessageEntity> messages) {
        List<ChatMessage> history = new ArrayList<>();
        if (summary != null && !summary.isBlank()) {
            history.add(SystemMessage.from(SUMMARY_HEADER + "\n" + summary.strip()));
        }
        for (TutorMessageEntity message : messages) {
            if (message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            history.add("assistant".equals(message.getRole())
                    ? AiMessage.from(message.getContent()) : UserMessage.from(message.getContent()));
        }
        return history;
    }

    static String transcript(List<TutorMessageEntity> messages) {
        List<String> lines = new ArrayList<>();
        for (TutorMessageEntity message : messages) {
            if (message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            lines.add(("assistant".equals(message.getRole()) ? "Assistant: " : "User: ") + message.getContent().strip());
        }
        return String.join("\n\n", lines);
    }

    static int tokens(List<ChatMessage> history) {
        int total = 0;
        for (ChatMessage message : history) {
            total += TokenEstimator.count(ChatAgentLoop.textOf(message));
        }
        return total;
    }

    static String trimIncompleteTail(String text) {
        int cut = text.lastIndexOf('\n');
        return cut > 0 ? text.substring(0, cut) : text;
    }
}
