package cn.utcy.teaching.question.infrastructure;

import cn.utcy.teaching.shared.course.CourseContentReferenceSource;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.question.application.CourseQuestionApplicationService;
import org.springframework.stereotype.Component;

@Component
class QuestionReferenceSource implements CourseContentReferenceSource {

    private final CourseQuestionApplicationService questions;

    QuestionReferenceSource(CourseQuestionApplicationService questions) {
        this.questions = questions;
    }

    @Override
    public boolean references(long courseId, CourseOutlineItemType itemType, long contentId) {
        return itemType == CourseOutlineItemType.QUESTION && questions.hasAttempts(courseId, contentId);
    }
}
