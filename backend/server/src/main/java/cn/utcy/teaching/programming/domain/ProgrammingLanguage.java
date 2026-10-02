package cn.utcy.teaching.programming.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum ProgrammingLanguage {
    C17("C17"),
    CPP20("CPP20"),
    PYTHON312("PYTHON312");

    @EnumValue
    private final String value;

    ProgrammingLanguage(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static ProgrammingLanguage fromValue(String value) {
        return Arrays.stream(values())
                .filter(language -> language.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的编程语言：" + value));
    }
}
