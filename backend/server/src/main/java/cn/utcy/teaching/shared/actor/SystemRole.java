package cn.utcy.teaching.shared.actor;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum SystemRole {
    ROOT("root"),
    ADMIN("admin"),
    TEACHER("teacher"),
    STUDENT("student");

    @EnumValue
    private final String value;

    SystemRole(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static SystemRole fromValue(String value) {
        return Arrays.stream(values())
                .filter(role -> role.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知系统角色：" + value));
    }

    public String authority() {
        return "ROLE_" + name();
    }

    public boolean isPlatformAdministrator() {
        return this == ROOT || this == ADMIN;
    }

    public boolean canManageTeachingContent() {
        return isPlatformAdministrator() || this == TEACHER;
    }
}
