package cn.utcy.teaching.knowledgegraph.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import org.springframework.modulith.NamedInterface;

import java.util.Arrays;

/** 知识点小类(与抽取提示词的枚举一致,数据库与接口都用中文值) */
@NamedInterface("vocabulary")
public enum KpType {
    CONCEPT("概念"),
    METHOD("方法"),
    SKILL("技能"),
    RULE("规则"),
    TOOL("工具"),
    PITFALL("易错点");

    @EnumValue
    private final String value;

    KpType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static KpType fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知知识点小类：" + value));
    }
}
