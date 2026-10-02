package cn.utcy.teaching.question.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.question.domain.CourseQuestionType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 单题草稿的规整与检查:type 强制为 template 题型,字母答案映射回选项原文,
 * 判断题中文答案转布尔;检查复用 CourseQuestionContract(与手工录入同一套);
 * 每题都交付,残余问题随题标记。
 */
@Component
public class QuestionDraftNormalizer {

    static final Map<CourseQuestionType, String> TYPE_LABELS = Map.of(
            CourseQuestionType.SINGLE_CHOICE, "单选题",
            CourseQuestionType.FILL_IN_BLANK, "填空题",
            CourseQuestionType.TRUE_FALSE, "判断题");

    private static final Set<String> TRUE_WORDS = Set.of("true", "t", "对", "正确", "yes", "y", "1");
    private static final Set<String> FALSE_WORDS = Set.of("false", "f", "错", "错误", "no", "n", "0");

    private final ObjectMapper objectMapper;
    private final CourseQuestionContract contract;

    public QuestionDraftNormalizer(ObjectMapper objectMapper, CourseQuestionContract contract) {
        this.objectMapper = objectMapper;
        this.contract = contract;
    }

    /** 草稿题:与 SaveCourseQuestionRequest 字段一致;issues 非空表示修复后仍有问题 */
    public record DraftQuestion(String title, CourseQuestionType type, String stemMarkdown,
                                JsonNode options, JsonNode answer, String analysisMarkdown,
                                List<String> issues) {
    }

    ObjectNode normalize(JsonNode payload, CourseQuestionType type) {
        JsonNode item = payload;
        if (payload != null && payload.isObject() && payload.has("items") && payload.path("items").isArray()
                && !payload.path("items").isEmpty()) {
            item = payload.path("items").get(0);
        } else if (payload != null && payload.isArray() && !payload.isEmpty()) {
            item = payload.get(0);
        }
        if (item == null || !item.isObject()) {
            return null;
        }
        ObjectNode question = objectMapper.createObjectNode();
        question.put("title", textOf(item.path("title")));
        question.put("type", type.value());
        question.put("stemMarkdown", textOf(item.path("stemMarkdown")));
        question.put("analysisMarkdown", textOf(item.path("analysisMarkdown")));

        boolean choice = type == CourseQuestionType.SINGLE_CHOICE;
        List<String> options = new ArrayList<>();
        if (choice && item.path("options").isArray()) {
            for (JsonNode option : item.path("options")) {
                String text = textOf(option);
                if (!text.isEmpty()) {
                    options.add(text);
                }
            }
        }
        if (choice && !options.isEmpty()) {
            ArrayNode optionsNode = question.putArray("options");
            options.forEach(optionsNode::add);
        } else {
            question.set("options", NullNode.getInstance());
        }
        question.set("answer", normalizeAnswer(item.path("answer"), type, options));
        return question;
    }

    private JsonNode normalizeAnswer(JsonNode answer, CourseQuestionType type, List<String> options) {
        switch (type) {
            case SINGLE_CHOICE -> {
                JsonNode unwrapped = answer.isArray() && answer.size() == 1 ? answer.get(0) : answer;
                if (unwrapped.isTextual()) {
                    return TextNode.valueOf(snapToOption(unwrapped.textValue().strip(), options));
                }
                return unwrapped.isMissingNode() ? NullNode.getInstance() : unwrapped;
            }
            case FILL_IN_BLANK -> {
                JsonNode unwrapped = answer.isArray() && answer.size() == 1 ? answer.get(0) : answer;
                if (unwrapped.isTextual()) {
                    return TextNode.valueOf(unwrapped.textValue().strip());
                }
                return unwrapped.isMissingNode() ? NullNode.getInstance() : unwrapped;
            }
            case TRUE_FALSE -> {
                if (answer.isBoolean()) {
                    return answer;
                }
                if (answer.isTextual()) {
                    String word = answer.textValue().strip().toLowerCase(Locale.ROOT);
                    if (TRUE_WORDS.contains(word)) {
                        return BooleanNode.TRUE;
                    }
                    if (FALSE_WORDS.contains(word)) {
                        return BooleanNode.FALSE;
                    }
                }
                return answer.isMissingNode() ? NullNode.getInstance() : answer;
            }
        }
        return answer;
    }

    private static String snapToOption(String answer, List<String> options) {
        if (options.isEmpty()) {
            return answer;
        }
        if (answer.length() == 1) {
            int index = Character.toUpperCase(answer.charAt(0)) - 'A';
            if (index >= 0 && index < options.size()) {
                return options.get(index);
            }
        }
        for (String option : options) {
            if (option.equalsIgnoreCase(answer)) {
                return option;
            }
        }
        return answer;
    }

    List<String> collectIssues(ObjectNode question, CourseQuestionType type, Set<String> usedTitles) {
        List<String> issues = new ArrayList<>();
        if (question == null) {
            issues.add("模型没有返回题目 JSON");
            return issues;
        }
        String title = question.path("title").asText("");
        if (title.isEmpty() || title.length() > 255) {
            issues.add("title 必须是 1~255 字符的考点短语");
        } else if (usedTitles.contains(title)) {
            issues.add("title \"" + title + "\" 与已有或本轮已出的试题重复,换一个考点");
        }
        String stem = question.path("stemMarkdown").asText("");
        if (stem.isBlank() || stem.length() > 100_000) {
            issues.add("stemMarkdown 必须是非空且不超过 100000 字符的题干");
        }
        String analysis = question.path("analysisMarkdown").asText("");
        if (analysis.isBlank() || analysis.length() > 100_000) {
            issues.add("analysisMarkdown 必须是非空且不超过 100000 字符的解析");
        }
        JsonNode options = question.path("options").isNull() ? null : question.path("options");
        JsonNode answer = question.path("answer");
        try {
            CourseQuestionContract.validateStem(type, stem);
        } catch (BadRequestException exception) {
            issues.add(exception.getMessage());
        }
        try {
            contract.validateDefinition(type, options, answer);
        } catch (BadRequestException exception) {
            issues.add(exception.getMessage());
        }
        return issues;
    }

    DraftQuestion toDraft(ObjectNode question, CourseQuestionType type, String topic, List<String> issues) {
        if (question == null) {
            return new DraftQuestion("[生成失败] " + topic, type, "", null, NullNode.getInstance(), "",
                    issues);
        }
        String title = question.path("title").asText("");
        if (title.isEmpty()) {
            title = "[生成失败] " + topic;
        }
        JsonNode options = question.path("options").isNull() ? null : question.path("options");
        return new DraftQuestion(
                title,
                type,
                question.path("stemMarkdown").asText(""),
                options,
                question.path("answer").isMissingNode() ? NullNode.getInstance() : question.path("answer"),
                question.path("analysisMarkdown").asText(""),
                issues);
    }

    static String textOf(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "";
        }
        return node.isTextual() ? node.textValue().strip() : node.asText("").strip();
    }

    /** 各题型的输出硬规则(进出题与修复的 system 提示词,与 collectIssues 检查一一对应) */
    static String typeRules(CourseQuestionType type) {
        return switch (type) {
            case SINGLE_CHOICE -> """
                    - type 固定为 "single_choice"
                    - options 是 4 个互不重复的选项文本(不带 A/B/C/D 前缀),每个不超过 500 字符
                    - answer 是**正确选项的原文字符串**(必须与 options 中某一项逐字相同),**绝不是** "A"/"B" 这样的字母
                    - 干扰项必须可信:与正确项长度、风格相近,**不要**把正确项写得明显更长更详细""";
            case FILL_IN_BLANK -> """
                    - type 固定为 "fill_in_blank"
                    - 题干中**必须恰好包含一处** `____`(四个下划线)标记缺失的词或短语,不能没有,也不能有两处
                    - options 必须为 null
                    - answer 是填入空白处的**一个词或短语**(字符串,不超过 200 字符);**不要**列多个并列答案——选一个最规范、唯一的;
                      空格处的答案必须能从题干唯一确定,避免开放式表述""";
            case TRUE_FALSE -> """
                    - type 固定为 "true_false"
                    - 题干是**一个可判断对错的完整命题**,不要写成"以下哪项"式的选择
                    - options 必须为 null
                    - answer 是布尔值:命题正确为 true,错误为 false""";
        };
    }
}
