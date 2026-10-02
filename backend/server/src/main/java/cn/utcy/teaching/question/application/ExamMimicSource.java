package cn.utcy.teaching.question.application;

import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.ai.structured.JsonResponseParser;
import cn.utcy.teaching.ai.llm.LlmCalls;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import cn.utcy.teaching.ai.document.MineruClient;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.question.application.QuestionAttachments.Attachment;
import cn.utcy.teaching.question.application.QuestionPipelineService.QuizTemplate;
import cn.utcy.teaching.question.domain.CourseQuestionType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 试卷仿写的素材适配:
 * 上传的试卷 PDF → MinerU 解析成 Markdown → 一次 LLM 抽题(题号 / 原文 / 题型 / 难度 / 参考答案)
 * → 转成逐题阶段的 mimic template(带原题与参考答案),探索与规划两阶段跳过。
 * 平台不支持的题型(多选 / 简答 / 论述 / 编程)按 notice 跳过,不做改写。
 */
@Component
public class ExamMimicSource {

    static final int PAPER_CHARS = 15_000;
    static final int TOPIC_CLIP_CHARS = 240;
    static final Set<String> DIFFICULTIES = Set.of("easy", "medium", "hard");
    static final Map<String, CourseQuestionType> SUPPORTED_TYPES = Map.of(
            "single_choice", CourseQuestionType.SINGLE_CHOICE,
            "fill_in_blank", CourseQuestionType.FILL_IN_BLANK,
            "true_false", CourseQuestionType.TRUE_FALSE);
    private static final Map<String, String> UNSUPPORTED_LABELS = Map.of(
            "multiple_choice", "多选题", "short_answer", "简答题", "written", "论述题", "coding", "编程题");

    private final MineruClient mineru;
    private final LlmCalls llm;
    private final PromptLoader prompts;
    private final ModelConfig llmModel;
    private final ObjectMapper objectMapper;

    public ExamMimicSource(MineruClient mineru, LlmCalls llm, PromptLoader prompts,
                           @Qualifier("questionLlmModel") ModelConfig llmModel, ObjectMapper objectMapper) {
        this.mineru = mineru;
        this.llm = llm;
        this.prompts = prompts;
        this.llmModel = llmModel;
        this.objectMapper = objectMapper;
    }

    public List<QuizTemplate> templates(String apiKey, Attachment paper, int maxQuestions,
                                        Consumer<String> onProgress, Consumer<String> onNotice,
                                        BooleanSupplier cancelled) {
        onProgress.accept("正在解析上传的试卷并抽取题目…");
        String markdown = mineru.parseToMarkdown(paper.mineruToken(), paper.fileUrl(), onProgress, cancelled);
        if (cancelled.getAsBoolean()) {
            return List.of();
        }
        onProgress.accept("试卷解析完成(" + markdown.length() + " 字符),正在用模型抽取题目…");
        PromptLoader.Prompt prompt = prompts.build("question/prompts", "extract",
                Map.of("paperMarkdown", Text.truncate(markdown, PAPER_CHARS)));
        String raw = llm.chatText(apiKey, llmModel, List.of(
                SystemMessage.from(prompt.system()), UserMessage.from(prompt.user())), true, null);
        JsonNode parsed = JsonResponseParser.parse(raw);
        if (parsed == null) {
            throw new IllegalStateException("试卷抽题失败:模型没有返回可解析的 JSON");
        }
        return toTemplates(parsed.path("questions"), maxQuestions, onNotice);
    }

    List<QuizTemplate> toTemplates(JsonNode questions, int maxQuestions, Consumer<String> onNotice) {
        List<QuizTemplate> templates = new ArrayList<>();
        if (!questions.isArray()) {
            return templates;
        }
        int index = 0;
        for (JsonNode item : questions) {
            if (maxQuestions > 0 && index >= maxQuestions) {
                break;
            }
            index++;
            if (!item.isObject()) {
                continue;
            }
            String text = item.path("question_text").asText("").strip();
            if (text.isEmpty()) {
                continue;
            }
            String typeValue = item.path("question_type").asText("").strip().toLowerCase();
            CourseQuestionType type = SUPPORTED_TYPES.get(typeValue);
            if (type == null) {
                String label = UNSUPPORTED_LABELS.getOrDefault(typeValue, "未识别题型「" + typeValue + "」");
                onNotice.accept("试卷第 " + item.path("question_number").asText(String.valueOf(index))
                        + " 题是" + label + ",平台仅支持单选 / 填空 / 判断,已跳过。");
                continue;
            }
            String difficulty = item.path("difficulty").asText("").strip().toLowerCase();
            if (!DIFFICULTIES.contains(difficulty)) {
                difficulty = "medium";
            }
            String answer = item.path("answer").asText("").strip();
            templates.add(new QuizTemplate("q_" + index,
                    text.length() > TOPIC_CLIP_CHARS ? text.substring(0, TOPIC_CLIP_CHARS) : text,
                    type, difficulty, "mimic", text, answer.isEmpty() ? null : answer));
        }
        return templates;
    }

}
