package cn.utcy.teaching.tutor.application;

import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.ai.llm.LlmCalls;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.ai.llm.ThinkingTags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class TutorTitleService {

    private static final Logger log = LoggerFactory.getLogger(TutorTitleService.class);
    static final long TIMEOUT_SECONDS = 20;
    static final int MAX_TITLE_CHARS = 80;
    private static final String[] PREFIXES = {"Title:", "title:", "TITLE:", "Title-", "标题:", "标题：", "对话标题:", "对话标题："};
    private static final String[][] QUOTES = {{"\"", "\""}, {"'", "'"}, {"“", "”"}, {"‘", "’"}, {"「", "」"},
            {"『", "』"}, {"`", "`"}};

    private final LlmCalls llm;
    private final PromptLoader prompts;
    private final ModelConfig model;

    TutorTitleService(LlmCalls llm, PromptLoader prompts, ModelConfig model) {
        this.llm = llm;
        this.prompts = prompts;
        this.model = new ModelConfig(model.id() + "-title", model.providerModel(), false, 0.3,
                model.topP(), 80);
    }

    String generate(String apiKey, String firstUser, String firstAssistant) {
        PromptLoader.Prompt prompt = prompts.build("tutor/prompts", "title", Map.of(
                "firstUser", Text.abbreviate(firstUser, 800), "firstAssistant", Text.abbreviate(firstAssistant, 1500)));
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> llm.chatText(apiKey, model, List.of(
                SystemMessage.from(prompt.system()), UserMessage.from(prompt.user())), false, null));
        try {
            String title = sanitize(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            if (!title.isEmpty()) {
                return title;
            }
        } catch (Exception exception) {
            log.warn("会话标题生成失败,回退首问:{}", exception.getMessage());
            future.cancel(true);
        }
        return fallback(firstUser);
    }

    static String fallback(String firstUser) {
        String text = firstUser.strip();
        return Text.abbreviate(text, 50);
    }

    static String sanitize(String raw) {
        String text = ThinkingTags.strip(raw == null ? "" : raw);
        int newline = text.indexOf('\n');
        if (newline >= 0) {
            text = text.substring(0, newline);
        }
        for (int pass = 0; pass < 8; pass++) {
            String before = text;
            text = text.strip().replaceAll("^[*_#\\-\\s]+", "").replaceAll("[*_#\\-\\s]+$", "");
            for (String prefix : PREFIXES) {
                if (text.startsWith(prefix)) {
                    text = text.substring(prefix.length()).strip();
                }
            }
            for (String[] pair : QUOTES) {
                if (text.length() >= 2 && text.startsWith(pair[0]) && text.endsWith(pair[1])) {
                    text = text.substring(1, text.length() - 1).strip();
                }
            }
            text = text.replaceAll("[.。!！?？,，;；、\\s\\t]+$", "");
            if (text.equals(before)) {
                break;
            }
        }
        return text.length() > MAX_TITLE_CHARS ? text.substring(0, MAX_TITLE_CHARS) : text;
    }

}
