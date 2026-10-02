package cn.utcy.teaching.programming.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum JudgeJobStatus {
    PENDING("pending"),
    PUBLISHED("published"),
    COMPLETED("completed");

    @EnumValue
    private final String value;

    JudgeJobStatus(String value) {
        this.value = value;
    }
}
