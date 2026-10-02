package cn.utcy.teaching.identity.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

public enum CredentialState {
    RESET_REQUIRED("reset_required"),
    ACTIVE("active");

    @EnumValue
    private final String value;

    CredentialState(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
