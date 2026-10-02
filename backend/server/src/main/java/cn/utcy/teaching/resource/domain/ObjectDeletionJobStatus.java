package cn.utcy.teaching.resource.domain;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum ObjectDeletionJobStatus {
    PENDING("pending"),
    COMPLETED("completed"),
    FAILED("failed");

    @EnumValue
    private final String value;

    ObjectDeletionJobStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
