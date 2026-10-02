package cn.utcy.teaching.question.infrastructure;

import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineSourceProvider;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.question.domain.CourseQuestion;
import cn.utcy.teaching.question.domain.CourseQuestionItem;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
class CourseQuestionOutlineSourceProvider implements CourseOutlineSourceProvider {

    private final CourseQuestionMapper questions;
    private final CourseQuestionItemMapper items;

    CourseQuestionOutlineSourceProvider(CourseQuestionMapper questions, CourseQuestionItemMapper items) {
        this.questions = questions;
        this.items = items;
    }

    @Override
    public CourseOutlineItemType type() {
        return CourseOutlineItemType.QUESTION;
    }

    @Override
    public Map<Long, String> requireTitles(long courseId, Set<Long> contentIds) {
        if (contentIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> titles = questions.selectList(new LambdaQueryWrapper<CourseQuestion>()
                        .eq(CourseQuestion::getCourseId, courseId)
                        .in(CourseQuestion::getId, contentIds))
                .stream()
                .collect(Collectors.toUnmodifiableMap(CourseQuestion::getId, CourseQuestion::getTitle));
        return titles;
    }

    @Override
    public String requireLinkable(long courseId, long contentId) {
        CourseQuestion question = questions.selectForUpdate(courseId, contentId);
        if (question == null) {
            throw new NotFoundException("课程中不存在该试题");
        }
        if (!items.exists(new LambdaQueryWrapper<CourseQuestionItem>().eq(CourseQuestionItem::getQuestionId, contentId))) {
            throw new ConflictException("试题还没有题目，不能加入课程内容");
        }
        return question.getTitle();
    }
}
