package cn.utcy.teaching.tutor.infrastructure;

import cn.utcy.teaching.shared.course.CourseDeletionGuard;
import org.springframework.stereotype.Component;

@Component
class TutorCourseDeletionGuard implements CourseDeletionGuard {

    private final TutorRowPurger purger;

    TutorCourseDeletionGuard(TutorRowPurger purger) {
        this.purger = purger;
    }

    @Override
    public void beforeCourseDeleted(long courseId) {
        purger.purgeCourse(courseId);
    }
}
