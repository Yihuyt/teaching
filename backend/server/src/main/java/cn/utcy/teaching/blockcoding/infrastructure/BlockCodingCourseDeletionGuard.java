package cn.utcy.teaching.blockcoding.infrastructure;

import cn.utcy.teaching.blockcoding.application.BlockCodingProjectPurger;
import cn.utcy.teaching.shared.course.CourseDeletionGuard;
import cn.utcy.teaching.blockcoding.domain.CourseBlockCodingConfig;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

@Component
class BlockCodingCourseDeletionGuard implements CourseDeletionGuard {
    private final CourseBlockCodingConfigMapper configs;
    private final BlockCodingProjectPurger projects;

    BlockCodingCourseDeletionGuard(CourseBlockCodingConfigMapper configs, BlockCodingProjectPurger projects) {
        this.configs = configs;
        this.projects = projects;
    }

    @Override
    public void beforeCourseDeleted(long courseId) {
        configs.delete(new LambdaQueryWrapper<CourseBlockCodingConfig>()
                .eq(CourseBlockCodingConfig::getCourseId, courseId));
        projects.purgeCourse(courseId);
    }
}
