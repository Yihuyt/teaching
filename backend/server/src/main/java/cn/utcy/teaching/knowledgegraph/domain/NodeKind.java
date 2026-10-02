package cn.utcy.teaching.knowledgegraph.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import org.springframework.modulith.NamedInterface;

import java.util.Arrays;

@NamedInterface("vocabulary")
public enum NodeKind {
    UNIT("unit", "章节"),
    KNOWLEDGE_POINT("knowledge_point", "知识点"),
    CODE_EXAMPLE("code_example", "代码示例");

    @EnumValue
    private final String value;
    private final String displayName;

    NodeKind(String value, String displayName) {
        this.value = value;
        this.displayName = displayName;
    }

    @JsonValue
    public String value() {
        return value;
    }

    public String displayName() {
        return displayName;
    }

    @JsonCreator
    public static NodeKind fromValue(String value) {
        return Arrays.stream(values())
                .filter(kind -> kind.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知节点类型：" + value));
    }
}
