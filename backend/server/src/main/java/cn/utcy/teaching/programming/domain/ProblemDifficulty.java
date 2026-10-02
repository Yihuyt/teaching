package cn.utcy.teaching.programming.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum ProblemDifficulty {
    EASY("easy"),
    MEDIUM("medium"),
    HARD("hard");

    @EnumValue
    private final String value;

    ProblemDifficulty(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ProblemDifficulty fromValue(String value) {
        return Arrays.stream(values())
                .filter(difficulty -> difficulty.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知题目难度：" + value));
    }
}
