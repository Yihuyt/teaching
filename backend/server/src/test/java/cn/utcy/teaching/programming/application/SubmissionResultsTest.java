package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.identity.application.AccountDirectory;
import cn.utcy.teaching.programming.application.SubmissionApplicationService.StudentSubmissionResultView;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.infrastructure.AccountSubmissionSummary;
import cn.utcy.teaching.programming.infrastructure.JudgeJobMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import cn.utcy.teaching.programming.infrastructure.SubmissionCaseResultMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionResultsTest {
    private final ProgrammingProblemApplicationService problems = mock(ProgrammingProblemApplicationService.class);
    private final ProgrammingSubmissionMapper submissions = mock(ProgrammingSubmissionMapper.class);
    private final AccountDirectory accounts = mock(AccountDirectory.class);
    private final CourseAccess courseAccess = mock(CourseAccess.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final SubmissionApplicationService service = new SubmissionApplicationService(
            problems, submissions, mock(SubmissionCaseResultMapper.class), mock(JudgeJobMapper.class),
            accounts, currentActor, courseAccess);

    @Test
    void everySubmitterIsListed() {
        Actor teacher = new Actor(7L, "teacher", SystemRole.TEACHER);
        when(currentActor.require()).thenReturn(teacher);
        when(problems.requireProblem(6L, 1L)).thenReturn(new ProgrammingProblem(
                1L, 7L, 6L, "题目", "题面", ProblemDifficulty.EASY, 1000, 256, 1024,
                "[\"CPP20\"]", null, null, null, Instant.EPOCH, Instant.EPOCH));
        when(submissions.summarizeByAccount(1L)).thenReturn(List.of(
                new AccountSubmissionSummary(7L, 2, true, BigDecimal.valueOf(100), Instant.EPOCH),
                new AccountSubmissionSummary(9L, 3, false, null, Instant.EPOCH)));
        when(accounts.require(7L)).thenReturn(new AccountDirectory.AccountSummary(7L, "t", "教师", SystemRole.TEACHER, true));
        when(accounts.require(9L)).thenReturn(new AccountDirectory.AccountSummary(9L, "s", "", SystemRole.STUDENT, true));

        List<StudentSubmissionResultView> rows = service.results(6L, 1L);

        verify(courseAccess).requireManagementAccess(6L, teacher);
        assertThat(rows).extracting(StudentSubmissionResultView::accountId).containsExactly(7L, 9L);
        assertThat(rows.get(1).accountName()).isEqualTo("s");
        assertThat(rows.get(1).bestScore()).isNull();
        assertThat(rows.get(1).accepted()).isFalse();
    }
}
