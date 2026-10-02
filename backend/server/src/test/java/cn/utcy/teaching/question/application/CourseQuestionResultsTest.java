package cn.utcy.teaching.question.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.identity.application.AccountDirectory;
import cn.utcy.teaching.question.application.CourseQuestionApplicationService.StudentResultView;
import cn.utcy.teaching.question.domain.CourseQuestion;
import cn.utcy.teaching.question.infrastructure.AccountAttemptSummary;
import cn.utcy.teaching.question.infrastructure.CourseQuestionAttemptMapper;
import cn.utcy.teaching.question.infrastructure.CourseQuestionItemMapper;
import cn.utcy.teaching.question.infrastructure.CourseQuestionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 成绩按课程成员统计,不区分角色:谁交过卷就列谁 */
class CourseQuestionResultsTest {

    private final CourseQuestionMapper questions = mock(CourseQuestionMapper.class);
    private final CourseQuestionAttemptMapper attempts = mock(CourseQuestionAttemptMapper.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final AccountDirectory accounts = mock(AccountDirectory.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CourseQuestionApplicationService service = new CourseQuestionApplicationService(
            questions, mock(CourseQuestionItemMapper.class), attempts, currentActor, mock(CourseAccess.class),
            mock(CourseOutlineLinks.class), new CourseQuestionContract(objectMapper),
            mock(LearningEventRecorder.class), accounts, objectMapper,
            mock(cn.utcy.teaching.question.infrastructure.CourseQuestionRowPurger.class), List.of());

    @Test
    void everyAttempterIsListed() {
        when(currentActor.require()).thenReturn(new Actor(7L, "teacher", SystemRole.TEACHER));
        when(questions.selectOne(any())).thenReturn(mock(CourseQuestion.class));
        when(attempts.summarizeByAccount(1L)).thenReturn(List.of(row(7L), row(9L), row(1L)));
        when(accounts.require(7L)).thenReturn(new AccountDirectory.AccountSummary(7L, "t", "教师", SystemRole.TEACHER, true));
        when(accounts.require(9L)).thenReturn(new AccountDirectory.AccountSummary(9L, "s", "学生", SystemRole.STUDENT, true));
        when(accounts.require(1L)).thenReturn(new AccountDirectory.AccountSummary(1L, "root", "系统管理员", SystemRole.ROOT, true));

        List<StudentResultView> rows = service.results(6L, 1L);

        assertThat(rows).extracting(StudentResultView::accountId).containsExactly(7L, 9L, 1L);
        assertThat(rows).extracting(StudentResultView::accountName).containsExactly("教师", "学生", "系统管理员");
    }

    private static AccountAttemptSummary row(long accountId) {
        return new AccountAttemptSummary(accountId, 1, 5.0, Instant.EPOCH);
    }
}
