package cn.utcy.teaching.knowledgebase.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** 知识库文档的入库状态机:pending → parsing → indexing → ready / error(可重试) */
public enum DocumentState {
    PENDING("pending"),
    PARSING("parsing"),
    INDEXING("indexing"),
    READY("ready"),
    ERROR("error");

    @EnumValue
    private final String value;

    DocumentState(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static DocumentState fromValue(String value) {
        return Arrays.stream(values())
                .filter(state -> state.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知文档状态：" + value));
    }
}
