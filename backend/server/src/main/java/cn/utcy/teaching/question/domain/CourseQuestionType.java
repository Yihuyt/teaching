package cn.utcy.teaching.question.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum CourseQuestionType {
    SINGLE_CHOICE("single_choice"),
    FILL_IN_BLANK("fill_in_blank"),
    TRUE_FALSE("true_false");

    @EnumValue
    private final String value;

    CourseQuestionType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CourseQuestionType fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知试题类型：" + value));
    }
}
