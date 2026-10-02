package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.programming.domain.JudgeJobStatus;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.domain.SubmissionStatus;
import cn.utcy.teaching.programming.infrastructure.JudgeJobMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import cn.utcy.teaching.programming.infrastructure.SubmissionCaseResultMapper;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RejudgeApplicationServiceTest {
    private static final String JOB_ID = "b88ccbba-9cca-49e7-8435-3d2313eb066a";
    private static final Actor TEACHER = new Actor(1L, "teacher", SystemRole.TEACHER);

    @Test
    void rejudgeResetsSubmissionAndJobIntoTheSameChannel() {
        Fixture fixture = fixture();
        when(fixture.jobs().selectBySubmissionForUpdate(7L)).thenReturn(completedJob(1));
        when(fixture.submissions().selectById(7L)).thenReturn(submission(7L, SubmissionStatus.ACCEPTED));
        when(fixture.submissions().resetForRejudge(eq(7L), any(Instant.class))).thenReturn(1);
        when(fixture.jobs().resetForRepublish(JOB_ID, 2, 0, "completed")).thenReturn(1);

        fixture.service().rejudgeSubmission(6L, 3L, 7L);

        verify(fixture.courseAccess()).requireManagementAccess(6L, TEACHER);
        verify(fixture.caseResults()).delete(any());
        verify(fixture.submissions()).resetForRejudge(eq(7L), any(Instant.class));
        verify(fixture.jobs()).resetForRepublish(JOB_ID, 2, 0, "completed");
    }

    @Test
    void nonManagerCannotRejudge() {
        Fixture fixture = fixture();
        doThrow(new ForbiddenOperationException("无权管理该课程"))
                .when(fixture.courseAccess()).requireManagementAccess(6L, TEACHER);

        assertThatThrownBy(() -> fixture.service().rejudgeSubmission(6L, 3L, 7L))
                .isInstanceOf(ForbiddenOperationException.class);
        assertThatThrownBy(() -> fixture.service().rejudgeProblem(6L, 3L, Set.of()))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(fixture.jobs(), never()).selectBySubmissionForUpdate(anyLong());
        verify(fixture.submissions(), never()).selectList(any());
    }

    @Test
    void submissionOfAnotherProblemIsNotFound() {
        Fixture fixture = fixture();
        when(fixture.submissions().selectById(7L)).thenReturn(submission(7L, SubmissionStatus.ACCEPTED));

        assertThatThrownBy(() -> fixture.service().rejudgeSubmission(6L, 4L, 7L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("课程中不存在该编程题");

        when(fixture.problems().selectById(4L)).thenReturn(problem(4L, 6L));
        assertThatThrownBy(() -> fixture.service().rejudgeSubmission(6L, 4L, 7L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("提交不存在");

        verify(fixture.jobs(), never()).selectBySubmissionForUpdate(anyLong());
    }

    @Test
    void inProgressSubmissionCannotBeRejudged() {
        Fixture fixture = fixture();
        when(fixture.jobs().selectBySubmissionForUpdate(7L)).thenReturn(completedJob(1));
        when(fixture.submissions().selectById(7L)).thenReturn(submission(7L, SubmissionStatus.QUEUED));

        assertThatThrownBy(() -> fixture.service().rejudgeSubmission(6L, 3L, 7L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("评测进行中，不能重判");

        verify(fixture.submissions(), never()).resetForRejudge(anyLong(), any(Instant.class));
        verify(fixture.jobs(), never()).resetForRepublish(anyString(), anyInt(), anyInt(), anyString());
    }

    @Test
    void batchRejudgeSummarizesSkippedAndFailed() {
        Fixture fixture = fixture();
        when(fixture.submissions().selectList(any())).thenReturn(List.of(
                submission(1L, SubmissionStatus.ACCEPTED),
                submission(2L, SubmissionStatus.QUEUED),
                submission(3L, SubmissionStatus.WRONG_ANSWER)));
        when(fixture.jobs().selectBySubmissionForUpdate(1L)).thenReturn(completedJob(1));
        when(fixture.jobs().selectBySubmissionForUpdate(2L)).thenReturn(completedJob(1));
        when(fixture.jobs().selectBySubmissionForUpdate(3L)).thenReturn(completedJob(1));
        when(fixture.submissions().selectById(1L)).thenReturn(submission(1L, SubmissionStatus.ACCEPTED));
        when(fixture.submissions().selectById(2L)).thenReturn(submission(2L, SubmissionStatus.QUEUED));
        when(fixture.submissions().selectById(3L)).thenReturn(submission(3L, SubmissionStatus.WRONG_ANSWER));
        when(fixture.submissions().resetForRejudge(eq(1L), any(Instant.class))).thenReturn(1);
        when(fixture.submissions().resetForRejudge(eq(3L), any(Instant.class))).thenReturn(0);
        when(fixture.jobs().resetForRepublish(anyString(), anyInt(), anyInt(), anyString())).thenReturn(1);

        RejudgeApplicationService.RejudgeSummary summary =
                fixture.service().rejudgeProblem(6L, 3L, Set.of());

        assertThat(summary.requeued()).isEqualTo(1);
        assertThat(summary.skippedInProgress()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
    }

    private static JudgeJob completedJob(int attempt) {
        return new JudgeJob(JOB_ID, 7L, attempt, JudgeJobStatus.COMPLETED, "9-0",
                Instant.EPOCH, Instant.EPOCH, Instant.EPOCH);
    }

    private static ProgrammingSubmission submission(long id, SubmissionStatus status) {
        boolean terminal = status.terminal();
        return new ProgrammingSubmission(id, 3L, 11L, ProgrammingLanguage.CPP20, "int main(){}",
                status,
                terminal ? 1 : null,
                terminal ? 256 : null,
                terminal ? new BigDecimal("100.00") : null,
                terminal ? "评测通过" : null,
                Instant.EPOCH,
                terminal ? Instant.EPOCH : null,
                Instant.EPOCH);
    }

    private static ProgrammingProblem problem(long id, long courseId) {
        return new ProgrammingProblem(
                id, 7L, courseId, "题目", "题面", ProblemDifficulty.EASY, 1000, 256, 1024,
                "[\"CPP20\"]", null, null, null, Instant.EPOCH, Instant.EPOCH);
    }

    private Fixture fixture() {
        ProgrammingSubmissionMapper submissions = mock(ProgrammingSubmissionMapper.class);
        JudgeJobMapper jobs = mock(JudgeJobMapper.class);
        SubmissionCaseResultMapper caseResults = mock(SubmissionCaseResultMapper.class);
        ProgrammingProblemMapper problems = mock(ProgrammingProblemMapper.class);
        when(problems.selectById(3L)).thenReturn(problem(3L, 6L));
        CourseAccess courseAccess = mock(CourseAccess.class);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(TEACHER);
        RejudgeApplicationService service = new RejudgeApplicationService(
                submissions,
                jobs,
                caseResults,
                problems,
                currentActor,
                courseAccess,
                new TransactionTemplate(mock(PlatformTransactionManager.class)));
        return new Fixture(service, submissions, jobs, caseResults, problems, courseAccess);
    }

    private record Fixture(
            RejudgeApplicationService service,
            ProgrammingSubmissionMapper submissions,
            JudgeJobMapper jobs,
            SubmissionCaseResultMapper caseResults,
            ProgrammingProblemMapper problems,
            CourseAccess courseAccess
    ) {
    }
}
