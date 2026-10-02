package cn.utcy.teaching.question.infrastructure;

import cn.utcy.teaching.question.domain.CourseQuestion;
import cn.utcy.teaching.question.domain.CourseQuestionAttempt;
import cn.utcy.teaching.question.domain.CourseQuestionItem;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 试题及其从属行(题目、作答)的显式删除(数据库不设外键);删试题 / 删课程共用,必须在业务事务内调用 */
@Component
public class CourseQuestionRowPurger {

    private final CourseQuestionMapper questions;
    private final CourseQuestionItemMapper items;
    private final CourseQuestionAttemptMapper attempts;

    CourseQuestionRowPurger(CourseQuestionMapper questions, CourseQuestionItemMapper items,
                            CourseQuestionAttemptMapper attempts) {
        this.questions = questions;
        this.items = items;
        this.attempts = attempts;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeQuestions(List<Long> questionIds) {
        if (questionIds.isEmpty()) {
            return;
        }
        attempts.delete(new LambdaQueryWrapper<CourseQuestionAttempt>()
                .in(CourseQuestionAttempt::getQuestionId, questionIds));
        items.delete(new LambdaQueryWrapper<CourseQuestionItem>()
                .in(CourseQuestionItem::getQuestionId, questionIds));
        questions.delete(new LambdaQueryWrapper<CourseQuestion>()
                .in(CourseQuestion::getId, questionIds));
    }
}
