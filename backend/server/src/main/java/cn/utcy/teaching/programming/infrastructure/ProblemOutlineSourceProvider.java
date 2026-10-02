package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineSourceProvider;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
class ProblemOutlineSourceProvider implements CourseOutlineSourceProvider {
    private final ProgrammingProblemMapper problems;

    ProblemOutlineSourceProvider(ProgrammingProblemMapper problems) {
        this.problems = problems;
    }

    @Override
    public CourseOutlineItemType type() {
        return CourseOutlineItemType.PROGRAMMING_PROBLEM;
    }

    @Override
    public Map<Long, String> requireTitles(long courseId, Set<Long> contentIds) {
        if (contentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> titles = problems.selectList(new LambdaQueryWrapper<ProgrammingProblem>()
                        .eq(ProgrammingProblem::getCourseId, courseId)
                        .in(ProgrammingProblem::getId, contentIds))
                .stream()
                .collect(Collectors.toUnmodifiableMap(ProgrammingProblem::getId, ProgrammingProblem::getTitle));
        return titles;
    }

    @Override
    public String requireLinkable(long courseId, long contentId) {
        ProgrammingProblem problem = problems.selectForUpdate(contentId);
        if (problem == null || problem.getCourseId() == null || problem.getCourseId() != courseId) {
            throw new NotFoundException("课程中不存在该编程题");
        }
        if (!problem.hasConfirmedTestcase()) {
            throw new ConflictException("编程题尚未配置测试数据");
        }
        return problem.getTitle();
    }
}
