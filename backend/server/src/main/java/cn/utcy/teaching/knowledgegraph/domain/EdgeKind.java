package cn.utcy.teaching.knowledgegraph.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import org.springframework.modulith.NamedInterface;

import java.util.Arrays;

/**
 * 知识点之间的语义关系:前置(source 是 target 的先修,有向无环)与相关(无向,按 id 序规范化存储)。
 * 结构关系(父子 / 顺序)由节点的 parent_id / position 表达,不是关系。
 */
@NamedInterface("vocabulary")
public enum EdgeKind {
    PREREQUISITE("prerequisite", "前置"),
    RELATED("related", "相关");

    @EnumValue
    private final String value;
    private final String displayName;

    EdgeKind(String value, String displayName) {
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
    public static EdgeKind fromValue(String value) {
        return Arrays.stream(values())
                .filter(kind -> kind.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知关系类型：" + value));
    }
}
