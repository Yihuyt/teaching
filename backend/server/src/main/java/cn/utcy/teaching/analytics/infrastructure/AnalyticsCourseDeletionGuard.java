package cn.utcy.teaching.analytics.infrastructure;

import cn.utcy.teaching.shared.course.CourseDeletionGuard;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

@Component
class AnalyticsCourseDeletionGuard implements CourseDeletionGuard {

    private final LearningEventMapper events;

    AnalyticsCourseDeletionGuard(LearningEventMapper events) {
        this.events = events;
    }

    @Override
    public void beforeCourseDeleted(long courseId) {
        events.delete(new LambdaQueryWrapper<LearningEventEntity>()
                .eq(LearningEventEntity::getCourseId, courseId));
    }
}
