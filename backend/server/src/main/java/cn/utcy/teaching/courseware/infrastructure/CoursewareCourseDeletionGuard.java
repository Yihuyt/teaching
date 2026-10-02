package cn.utcy.teaching.courseware.infrastructure;

import cn.utcy.teaching.shared.course.CourseDeletionGuard;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class CoursewareCourseDeletionGuard implements CourseDeletionGuard {

    private final CoursewareMapper coursewares;
    private final CoursewareRowPurger purger;

    CoursewareCourseDeletionGuard(CoursewareMapper coursewares, CoursewareRowPurger purger) {
        this.coursewares = coursewares;
        this.purger = purger;
    }

    @Override
    public void beforeCourseDeleted(long courseId) {
        purger.purgeCoursewares(coursewares.selectList(new LambdaQueryWrapper<CoursewareEntity>()
                        .select(CoursewareEntity::getId)
                        .eq(CoursewareEntity::getCourseId, courseId))
                .stream()
                .map(CoursewareEntity::getId)
                .toList());
    }
}
