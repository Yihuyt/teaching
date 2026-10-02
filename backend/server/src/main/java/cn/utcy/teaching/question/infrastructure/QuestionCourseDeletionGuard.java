package cn.utcy.teaching.question.infrastructure;

import cn.utcy.teaching.shared.course.CourseDeletionGuard;
import cn.utcy.teaching.question.domain.CourseQuestion;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class QuestionCourseDeletionGuard implements CourseDeletionGuard {

    private final CourseQuestionMapper questions;
    private final CourseQuestionRowPurger purger;

    QuestionCourseDeletionGuard(CourseQuestionMapper questions, CourseQuestionRowPurger purger) {
        this.questions = questions;
        this.purger = purger;
    }

    @Override
    public void beforeCourseDeleted(long courseId) {
        List<Long> questionIds = questions.selectList(new LambdaQueryWrapper<CourseQuestion>()
                        .select(CourseQuestion::getId)
                        .eq(CourseQuestion::getCourseId, courseId))
                .stream()
                .map(CourseQuestion::getId)
                .toList();
        purger.purgeQuestions(questionIds);
    }
}
