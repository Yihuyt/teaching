package cn.utcy.teaching.knowledgegraph.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** 构建任务状态机:parsing → toc_ready →(确认+启动)extracting → extracted →(命名入库,构建行删除);运行态可 → failed(可重试) */
public enum BuildStatus {
    PARSING("parsing"),
    TOC_READY("toc_ready"),
    EXTRACTING("extracting"),
    EXTRACTED("extracted"),
    FAILED("failed");

    @EnumValue
    private final String value;

    BuildStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static BuildStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知构建状态：" + value));
    }
}
