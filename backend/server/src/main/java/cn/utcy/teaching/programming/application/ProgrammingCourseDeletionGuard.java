package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.course.CourseDeletionGuard;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class ProgrammingCourseDeletionGuard implements CourseDeletionGuard {
    private final ProgrammingProblemMapper problems;
    private final ProgrammingPurger purger;

    ProgrammingCourseDeletionGuard(
            ProgrammingProblemMapper problems,
            ProgrammingPurger purger
    ) {
        this.problems = problems;
        this.purger = purger;
    }

    @Override
    public void beforeCourseDeleted(long courseId) {
        List<ProgrammingProblem> owned = problems.selectList(
                new LambdaQueryWrapper<ProgrammingProblem>()
                        .select(ProgrammingProblem::getId)
                        .eq(ProgrammingProblem::getCourseId, courseId));
        purger.purgeProblems(owned.stream().map(ProgrammingProblem::getId).toList());
    }
}
