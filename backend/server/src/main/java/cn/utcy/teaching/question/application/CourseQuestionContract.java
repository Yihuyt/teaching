package cn.utcy.teaching.question.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.question.domain.CourseQuestionType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class CourseQuestionContract {

    /** 填空题题干里标记空格的记号(恰好一处) */
    public static final String BLANK_MARK = "____";
    static final int MAX_BLANK_ANSWER_CHARS = 200;
    static final int MAX_OPTION_CHARS = 500;

    private final ObjectMapper objectMapper;

    public CourseQuestionContract(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    EncodedPayload encodeDefinition(
            CourseQuestionType type,
            JsonNode options,
            JsonNode answer
    ) {
        JsonNode normalizedOptions = options == null || options.isNull() ? null : options;
        validateDefinition(type, normalizedOptions, answer);
        return new EncodedPayload(
                normalizedOptions == null ? null : writeJson(normalizedOptions),
                writeJson(answer));
    }

    public void validateDefinition(
            CourseQuestionType type,
            JsonNode options,
            JsonNode answer
    ) {
        if (type == null) {
            throw new BadRequestException("试题类型不能为空");
        }
        if (answer == null || answer.isNull()) {
            throw new BadRequestException("试题标准答案不能为空");
        }
        boolean choice = type == CourseQuestionType.SINGLE_CHOICE;
        if (choice && (options == null || !options.isArray() || options.size() < 2)) {
            throw new BadRequestException("至少需要两个选项");
        }
        if (!choice && options != null) {
            throw new BadRequestException("非选择题不能提供选项");
        }
        if (choice) {
            validateChoiceDefinition(options, answer);
        } else if (type == CourseQuestionType.FILL_IN_BLANK) {
            if (!answer.isTextual() || answer.textValue().isBlank()
                    || answer.textValue().length() > MAX_BLANK_ANSWER_CHARS) {
                throw new BadRequestException("填空题标准答案必须是非空且不超过 200 字符的字符串");
            }
        } else if (type == CourseQuestionType.TRUE_FALSE && !answer.isBoolean()) {
            throw new BadRequestException("判断题标准答案必须是布尔值");
        }
    }

    public static void validateStem(CourseQuestionType type, String stemMarkdown) {
        int marks = countBlankMarks(stemMarkdown == null ? "" : stemMarkdown);
        if (type == CourseQuestionType.FILL_IN_BLANK && marks != 1) {
            throw new BadRequestException("填空题题干必须恰好包含一处 " + BLANK_MARK + " 作为空格");
        }
        if (type != CourseQuestionType.FILL_IN_BLANK && marks > 0) {
            throw new BadRequestException("只有填空题的题干可以包含 " + BLANK_MARK);
        }
    }

    private static int countBlankMarks(String stem) {
        int count = 0;
        int index = 0;
        while ((index = stem.indexOf(BLANK_MARK, index)) >= 0) {
            count++;
            index += BLANK_MARK.length();
            // 连续更长的下划线串算同一处空格
            while (index < stem.length() && stem.charAt(index) == '_') {
                index++;
            }
        }
        return count;
    }

    private void validateChoiceDefinition(JsonNode options, JsonNode answer) {
        if (options.size() > 100) {
            throw new BadRequestException("选择题最多包含 100 个选项");
        }
        Set<String> optionValues = new HashSet<>();
        java.util.List<String> ordered = new java.util.ArrayList<>();
        int index = 0;
        for (JsonNode option : options) {
            String letter = String.valueOf((char) ('A' + index));
            if (!option.isTextual() || option.textValue().isBlank()) {
                throw new BadRequestException("选项 " + letter + " 不能为空");
            }
            if (option.textValue().length() > MAX_OPTION_CHARS) {
                throw new BadRequestException("选项 " + letter + " 不能超过 " + MAX_OPTION_CHARS + " 个字符");
            }
            if (!optionValues.add(option.textValue())) {
                String first = String.valueOf((char) ('A' + ordered.indexOf(option.textValue())));
                throw new BadRequestException("选项 " + first + " 与 " + letter + " 内容重复");
            }
            ordered.add(option.textValue());
            index++;
        }
        if (!answer.isTextual() || !optionValues.contains(answer.textValue())) {
            throw new BadRequestException("单选题标准答案必须是一个已有选项");
        }
    }

    private String writeJson(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("试题答案必须是有效 JSON");
        }
    }

    record EncodedPayload(String optionsJson, String answerJson) {
    }

}
