package cn.utcy.teaching.shared.course;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum CourseOutlineItemType {
    MATERIAL("material"),
    QUESTION("question"),
    PROGRAMMING_PROBLEM("programming_problem");

    @EnumValue
    private final String value;

    CourseOutlineItemType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static CourseOutlineItemType fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知课程内容类型：" + value));
    }
}
