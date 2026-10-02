package cn.utcy.teaching.programming.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum SubmissionStatus {
    QUEUED("QUEUED", false),
    ACCEPTED("ACCEPTED", true),
    WRONG_ANSWER("WRONG_ANSWER", true),
    COMPILE_ERROR("COMPILE_ERROR", true),
    RUNTIME_ERROR("RUNTIME_ERROR", true),
    TIME_LIMIT_EXCEEDED("TIME_LIMIT_EXCEEDED", true),
    MEMORY_LIMIT_EXCEEDED("MEMORY_LIMIT_EXCEEDED", true),
    OUTPUT_LIMIT_EXCEEDED("OUTPUT_LIMIT_EXCEEDED", true),
    SYSTEM_ERROR("SYSTEM_ERROR", true),
    WORKER_CRASH_LIMIT("WORKER_CRASH_LIMIT", true);

    @EnumValue
    private final String value;
    private final boolean terminal;

    SubmissionStatus(String value, boolean terminal) {
        this.value = value;
        this.terminal = terminal;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public boolean terminal() {
        return terminal;
    }

    @JsonCreator
    public static SubmissionStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知判题状态：" + value));
    }
}
