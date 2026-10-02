package cn.utcy.teaching.shared.learning;

import java.util.Map;

public record LearningEvent(
        long courseId,
        long accountId,
        LearningEventType type,
        long objectId,
        Map<String, Object> detail
) {
}
