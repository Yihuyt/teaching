package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.shared.course.CourseContentReferenceSource;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.programming.application.ProgrammingProblemApplicationService;
import org.springframework.stereotype.Component;

@Component
class ProblemReferenceSource implements CourseContentReferenceSource {
    private final ProgrammingProblemApplicationService problems;

    ProblemReferenceSource(ProgrammingProblemApplicationService problems) {
        this.problems = problems;
    }

    @Override
    public boolean references(long courseId, CourseOutlineItemType itemType, long contentId) {
        return itemType == CourseOutlineItemType.PROGRAMMING_PROBLEM
                && problems.hasSubmissions(courseId, contentId);
    }
}
