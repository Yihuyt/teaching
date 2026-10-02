package cn.utcy.teaching.notification.infrastructure;

import cn.utcy.teaching.shared.course.CourseDeletionGuard;
import cn.utcy.teaching.notification.domain.Announcement;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

@Component
class AnnouncementCourseDeletionGuard implements CourseDeletionGuard {

    private final AnnouncementMapper announcements;

    AnnouncementCourseDeletionGuard(AnnouncementMapper announcements) {
        this.announcements = announcements;
    }

    @Override
    public void beforeCourseDeleted(long courseId) {
        announcements.delete(new LambdaQueryWrapper<Announcement>().eq(Announcement::getCourseId, courseId));
    }
}
