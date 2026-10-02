package cn.utcy.teaching.resource.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MaterialState {
    PENDING_UPLOAD("pending_upload"),
    ACTIVE("active");

    @EnumValue
    private final String value;

    MaterialState(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
