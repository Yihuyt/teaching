package cn.utcy.teaching.resource.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MaterialKind {
    FOLDER("folder"),
    FILE("file");

    @EnumValue
    private final String value;

    MaterialKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
