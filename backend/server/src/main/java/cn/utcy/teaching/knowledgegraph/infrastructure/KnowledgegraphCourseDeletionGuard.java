package cn.utcy.teaching.knowledgegraph.infrastructure;

import cn.utcy.teaching.shared.course.CourseDeletionGuard;
import org.springframework.stereotype.Component;

@Component
class KnowledgegraphCourseDeletionGuard implements CourseDeletionGuard {

    private final KnowledgeGraphRowPurger purger;

    KnowledgegraphCourseDeletionGuard(KnowledgeGraphRowPurger purger) {
        this.purger = purger;
    }

    @Override
    public void beforeCourseDeleted(long courseId) {
        purger.purgeCourse(courseId);
    }
}
