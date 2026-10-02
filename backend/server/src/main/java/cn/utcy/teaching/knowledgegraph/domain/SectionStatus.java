package cn.utcy.teaching.knowledgegraph.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** 抽取子片状态:重抽只跑 pending / failed 片;failed 片可整体忽略(不进图)后直接合并 */
public enum SectionStatus {
    PENDING("pending"),
    RUNNING("running"),
    DONE("done"),
    FAILED("failed"),
    IGNORED("ignored");

    @EnumValue
    private final String value;

    SectionStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static SectionStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知抽取状态：" + value));
    }
}
