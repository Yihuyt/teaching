package cn.utcy.teaching.analytics.application;

import cn.utcy.teaching.shared.learning.LearningEventType;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record AnalyticsEvent(long accountId, LearningEventType type, long objectId, JsonNode detail,
                             Instant occurredAt) {

    public boolean detailBoolean(String field) {
        return detail != null && detail.path(field).asBoolean(false);
    }

    public String detailText(String field) {
        return detail == null ? "" : detail.path(field).asText("");
    }

    public double detailDouble(String field) {
        return detail == null ? 0 : detail.path(field).asDouble(0);
    }

    public long detailLong(String field) {
        return detail == null ? 0 : detail.path(field).asLong(0);
    }
}
