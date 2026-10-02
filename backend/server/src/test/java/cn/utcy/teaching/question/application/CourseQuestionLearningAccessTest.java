package cn.utcy.teaching.question.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.course.CourseContentDeletionGuard;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.identity.application.AccountDirectory;
import cn.utcy.teaching.question.domain.CourseQuestion;
import cn.utcy.teaching.question.domain.CourseQuestionItem;
import cn.utcy.teaching.question.domain.CourseQuestionType;
import cn.utcy.teaching.question.infrastructure.CourseQuestionAttemptMapper;
import cn.utcy.teaching.question.infrastructure.CourseQuestionItemMapper;
import cn.utcy.teaching.question.infrastructure.CourseQuestionMapper;
import cn.utcy.teaching.question.infrastructure.CourseQuestionRowPurger;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseQuestionLearningAccessTest {

    @Test
    @SuppressWarnings("unchecked")
    void learningViewContainsNoAnswerOrAnalysis() throws Exception {
        Fixture fixture = new Fixture();
        when(fixture.outlineLinks.isLinked(20L, CourseOutlineItemType.QUESTION, 10L)).thenReturn(true);
        when(fixture.questions.selectOne(any(LambdaQueryWrapper.class))).thenReturn(question());
        CourseQuestionItem item = new CourseQuestionItem(10L, 1, CourseQuestionType.SINGLE_CHOICE, "请选择正确答案",
                "[\"错误答案\",\"正确答案\"]", "\"正确答案\"", "答案解析", 10);
        setId(item, 101L);
        when(fixture.items.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(item));
        when(fixture.attempts.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());

        var result = fixture.service.getForLearning(20L, 10L);
        String responseJson = fixture.objectMapper.writeValueAsString(result);

        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).options()).hasSize(2);
        assertThat(result.totalScore()).isEqualTo(10);
        assertThat(result.canStart()).isTrue();
        assertThat(responseJson).doesNotContain("\"answer\"", "答案解析");
        assertThat(result.items().get(0).getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("answer", "answerJson", "analysisMarkdown");
        verify(fixture.courseAccess).requireLearningAccess(20L, fixture.actor);
    }

    @Test
    void questionOutsideLearningOutlineIsNotReadable() {
        Fixture fixture = new Fixture();
        when(fixture.outlineLinks.isLinked(20L, CourseOutlineItemType.QUESTION, 10L)).thenReturn(false);

        assertThatThrownBy(() -> fixture.service.getForLearning(20L, 10L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("课程内容中不存在该试题");
        assertThatThrownBy(() -> fixture.service.startAttempt(20L, 10L))
                .isInstanceOf(NotFoundException.class);

        verify(fixture.courseAccess, org.mockito.Mockito.times(2)).requireLearningAccess(20L, fixture.actor);
        verify(fixture.questions, never()).selectOne(any());
    }

    @Test
    void deletingQuestionsUnlinksAndNotifiesGuardsPerQuestionThenPurgesOnce() {
        Fixture fixture = new Fixture();
        when(fixture.questions.selectForUpdate(20L, 10L)).thenReturn(question(10L));
        when(fixture.questions.selectForUpdate(20L, 11L)).thenReturn(question(11L));

        fixture.service.delete(20L, List.of(10L, 11L));

        InOrder order = inOrder(fixture.outlineLinks, fixture.deletionGuard, fixture.purger);
        order.verify(fixture.outlineLinks).unlink(20L, CourseOutlineItemType.QUESTION, 10L);
        order.verify(fixture.deletionGuard).beforeContentDeleted(20L, CourseOutlineItemType.QUESTION, 10L);
        order.verify(fixture.outlineLinks).unlink(20L, CourseOutlineItemType.QUESTION, 11L);
        order.verify(fixture.deletionGuard).beforeContentDeleted(20L, CourseOutlineItemType.QUESTION, 11L);
        order.verify(fixture.purger).purgeQuestions(List.of(10L, 11L));
        verify(fixture.courseAccess).requireManagementAccess(20L, fixture.actor);
        verify(fixture.outlineLinks, never()).isLinked(anyLong(), any(), anyLong());
        verify(fixture.questions, never()).deleteById(anyLong());
    }

    private static CourseQuestion question() {
        return question(10L);
    }

    private static CourseQuestion question(long id) {
        CourseQuestion question = CourseQuestion.create(20L, "第一章测验", new CourseQuestion.Settings(null, true, true));
        setId(question, id);
        return question;
    }

    private static void setId(Object entity, long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class Fixture {
        private final Actor actor = new Actor(9L, "student", SystemRole.STUDENT);
        private final CourseQuestionMapper questions = mock(CourseQuestionMapper.class);
        private final CourseQuestionItemMapper items = mock(CourseQuestionItemMapper.class);
        private final CourseQuestionAttemptMapper attempts = mock(CourseQuestionAttemptMapper.class);
        private final CurrentActor currentActor = mock(CurrentActor.class);
        private final CourseAccess courseAccess = mock(CourseAccess.class);
        private final CourseOutlineLinks outlineLinks = mock(CourseOutlineLinks.class);
        private final CourseQuestionRowPurger purger = mock(CourseQuestionRowPurger.class);
        private final CourseContentDeletionGuard deletionGuard = mock(CourseContentDeletionGuard.class);
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final CourseQuestionApplicationService service;

        private Fixture() {
            when(currentActor.require()).thenReturn(actor);
            service = new CourseQuestionApplicationService(questions, items, attempts, currentActor, courseAccess,
                    outlineLinks, new CourseQuestionContract(objectMapper),
                    mock(LearningEventRecorder.class), mock(AccountDirectory.class), objectMapper,
                    purger, List.of(deletionGuard));
        }
    }
}
