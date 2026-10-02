package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseContentDeletionGuard;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.storage.ObjectStorageDeletionQueue;
import cn.utcy.teaching.judgecontract.TestcasePackageReader;
import cn.utcy.teaching.judgecontract.TestcasePackageWriter;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.identity.application.AccountDirectory;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.infrastructure.JudgeJobMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemProvenanceMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemSampleMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import cn.utcy.teaching.programming.infrastructure.SubmissionCaseResultMapper;
import cn.utcy.teaching.programming.infrastructure.TestcaseOssProperties;
import cn.utcy.teaching.programming.infrastructure.TestcaseObjectValidator;
import cn.utcy.teaching.programming.infrastructure.TransactionalTestcaseObjectWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgrammingConcurrencyTest {
    private static final String SHA_A = "a".repeat(64);

    @Test
    void testcaseDeletionLocksProblemRow() {
        Fixture fixture = new Fixture();
        when(fixture.problems.selectForUpdate(1L)).thenReturn(problem(SHA_A));
        when(fixture.problems.updateById((ProgrammingProblem) any())).thenReturn(1);

        fixture.testcases.delete(6L, 1L);

        verify(fixture.problems).selectForUpdate(1L);
        verify(fixture.problems, never()).selectById(1L);
        verify(fixture.deletionQueue).enqueue(
                "testcases",
                "judge-testcases/1/" + SHA_A + ".zip");
    }

    @Test
    void updateLocksBeforeHistoryCheckAndZeroAffectedRowIsConflict() {
        Fixture fixture = new Fixture();
        when(fixture.problems.selectForUpdate(1L)).thenReturn(problem(null));
        when(fixture.problems.updateById((ProgrammingProblem) any())).thenReturn(0);

        assertThatThrownBy(() -> fixture.problemService.update(6L, 1L, draft()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("编程题状态已变化，更新未生效");

        InOrder order = inOrder(fixture.problems, fixture.submissions);
        order.verify(fixture.problems).selectForUpdate(1L);
        order.verify(fixture.submissions).exists(any());
        order.verify(fixture.problems).updateById((ProgrammingProblem) any());
    }

    /** 课程内容项不锁住测试数据:在课程内容里的题也能删测试数据(提交时再拒绝未配置的题) */
    @Test
    void problemInOutlineCanStillHaveTestcaseDeleted() {
        Fixture fixture = new Fixture();
        ProgrammingProblem problem = problem(SHA_A);
        when(fixture.problems.selectForUpdate(1L)).thenReturn(problem);
        when(fixture.problems.updateById((ProgrammingProblem) any())).thenReturn(1);

        fixture.testcases.delete(6L, 1L);

        verify(fixture.courseAccess).requireManagementAccess(eq(6L), any());
        verify(fixture.courseOutlineLinks, never()).isLinked(anyLong(), any(), anyLong());
        verify(fixture.deletionQueue).enqueue(
                "testcases",
                "judge-testcases/1/" + SHA_A + ".zip");
        verify(fixture.problems).updateById(problem);
        assertThat(problem.hasConfirmedTestcase()).isFalse();
    }

    @Test
    void deletionUnlinksAndNotifiesGuardsBeforePurge() {
        Fixture fixture = new Fixture();
        when(fixture.problems.selectForUpdate(1L)).thenReturn(problem(SHA_A));

        fixture.problemService.delete(6L, List.of(1L));

        InOrder order = inOrder(
                fixture.problems, fixture.courseOutlineLinks, fixture.deletionGuard, fixture.purger);
        order.verify(fixture.problems).selectForUpdate(1L);
        order.verify(fixture.courseOutlineLinks)
                .unlink(6L, CourseOutlineItemType.PROGRAMMING_PROBLEM, 1L);
        order.verify(fixture.deletionGuard)
                .beforeContentDeleted(6L, CourseOutlineItemType.PROGRAMMING_PROBLEM, 1L);
        order.verify(fixture.purger).purgeProblems(List.of(1L));
        verify(fixture.courseAccess).requireManagementAccess(eq(6L), any());
        verify(fixture.courseOutlineLinks, never()).isLinked(anyLong(), any(), anyLong());
        verify(fixture.problems, never()).deleteById(anyLong());
    }

    @Test
    void nonManagerCannotCreateProblem() {
        Fixture fixture = new Fixture();
        Actor student = new Actor(9L, "student", SystemRole.STUDENT);
        when(fixture.currentActor.require()).thenReturn(student);
        doThrow(new ForbiddenOperationException("无权管理该课程"))
                .when(fixture.courseAccess).requireManagementAccess(6L, student);

        assertThatThrownBy(() -> fixture.problemService.create(6L, draft()))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(fixture.problems, never()).insert((ProgrammingProblem) any());
    }

    @Test
    void zeroAffectedProblemInsertIsExplicitConflict() {
        Fixture fixture = new Fixture();
        when(fixture.problems.insert((ProgrammingProblem) any())).thenReturn(0);

        assertThatThrownBy(() -> fixture.problemService.create(6L, draft()))
                .isInstanceOf(ConflictException.class)
                .hasMessage("编程题创建未生效");
    }

    @Test
    void submitLocksProblemBeforeCreatingSubmission() {
        Fixture fixture = new Fixture();
        fixture.linkToOutline();
        when(fixture.problems.selectForUpdate(1L)).thenReturn(problem(SHA_A));
        when(fixture.submissions.insert((ProgrammingSubmission) any()))
                .thenAnswer(invocation -> {
                    ProgrammingSubmission submission = invocation.getArgument(0);
                    ReflectionTestUtils.setField(submission, "id", 99L);
                    return 1;
                });
        when(fixture.jobs.insert((JudgeJob) any())).thenReturn(1);

        SubmissionApplicationService.SubmissionView result = fixture.submissionService.submit(
                6L, 1L, ProgrammingLanguage.CPP20, "int main() { return 0; }");

        assertThat(result.id()).isEqualTo(99L);
        assertThat(result.courseId()).isEqualTo(6L);
        InOrder order = inOrder(fixture.problems, fixture.submissions);
        order.verify(fixture.problems).selectForUpdate(1L);
        order.verify(fixture.submissions).insert((ProgrammingSubmission) any());
    }

    @Test
    void zeroAffectedSubmissionInsertIsExplicitConflict() {
        Fixture fixture = new Fixture();
        fixture.linkToOutline();
        when(fixture.problems.selectForUpdate(1L)).thenReturn(problem(SHA_A));
        when(fixture.submissions.insert((ProgrammingSubmission) any())).thenReturn(0);

        assertThatThrownBy(() -> fixture.submissionService.submit(
                6L, 1L, ProgrammingLanguage.CPP20, "int main() { return 0; }"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("提交记录创建未生效");

        verify(fixture.jobs, never()).insert((JudgeJob) any());
    }

    @Test
    void zeroAffectedJudgeJobInsertIsExplicitConflict() {
        Fixture fixture = new Fixture();
        fixture.linkToOutline();
        when(fixture.problems.selectForUpdate(1L)).thenReturn(problem(SHA_A));
        when(fixture.submissions.insert((ProgrammingSubmission) any()))
                .thenAnswer(invocation -> {
                    ProgrammingSubmission submission = invocation.getArgument(0);
                    ReflectionTestUtils.setField(submission, "id", 99L);
                    return 1;
                });
        when(fixture.jobs.insert((JudgeJob) any())).thenReturn(0);

        assertThatThrownBy(() -> fixture.submissionService.submit(
                6L, 1L, ProgrammingLanguage.CPP20, "int main() { return 0; }"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("判题任务创建未生效");
    }

    private static ProgrammingProblemApplicationService.ProblemDraft draft() {
        return new ProgrammingProblemApplicationService.ProblemDraft(
                "题目",
                "题面",
                ProblemDifficulty.EASY,
                1000,
                256,
                1024,
                Set.of(ProgrammingLanguage.CPP20),
                List.of());
    }

    private static ProgrammingProblem problem(String testcaseSha256) {
        Instant now = Instant.parse("2026-07-23T00:00:00Z");
        return new ProgrammingProblem(
                1L,
                7L,
                6L,
                "题目",
                "题面",
                ProblemDifficulty.EASY,
                1000,
                256,
                1024,
                "[\"CPP20\"]",
                testcaseSha256,
                testcaseSha256 == null ? null : 128L,
                testcaseSha256 == null ? null : now,
                now,
                now);
    }

    private static final class Fixture {
        private final ProgrammingProblemMapper problems = mock(ProgrammingProblemMapper.class);
        private final ProgrammingProblemSampleMapper samples =
                mock(ProgrammingProblemSampleMapper.class);
        private final ProgrammingProblemProvenanceMapper provenance =
                mock(ProgrammingProblemProvenanceMapper.class);
        private final ProgrammingSubmissionMapper submissions =
                mock(ProgrammingSubmissionMapper.class);
        private final SubmissionCaseResultMapper caseResults =
                mock(SubmissionCaseResultMapper.class);
        private final JudgeJobMapper jobs = mock(JudgeJobMapper.class);
        private final CurrentActor currentActor = mock(CurrentActor.class);
        private final TestcaseObjectValidator testcaseValidator =
                mock(TestcaseObjectValidator.class);
        private final TestcasePackageWriter testcaseWriter =
                mock(TestcasePackageWriter.class);
        private final TestcasePackageReader testcaseReader =
                mock(TestcasePackageReader.class);
        private final TransactionalTestcaseObjectWriter transactionalWriter =
                mock(TransactionalTestcaseObjectWriter.class);
        private final ObjectStorageDeletionQueue deletionQueue =
                mock(ObjectStorageDeletionQueue.class);
        private final CourseOutlineLinks courseOutlineLinks = mock(CourseOutlineLinks.class);
        private final CourseAccess courseAccess = mock(CourseAccess.class);
        private final ProgrammingPurger purger = mock(ProgrammingPurger.class);
        private final CourseContentDeletionGuard deletionGuard =
                mock(CourseContentDeletionGuard.class);
        private final ProgrammingProblemApplicationService problemService;
        private final TestcaseApplicationService testcases;
        private final SubmissionApplicationService submissionService;

        private Fixture() {
            when(currentActor.require())
                    .thenReturn(new Actor(7L, "teacher", SystemRole.TEACHER));
            problemService = new ProgrammingProblemApplicationService(
                    problems,
                    samples,
                    provenance,
                    submissions,
                    currentActor,
                    new ObjectMapper(),
                    courseOutlineLinks,
                    courseAccess,
                    purger,
                    List.of(deletionGuard));
            testcases = new TestcaseApplicationService(
                    problemService,
                    problems,
                    new TestcaseOssProperties("testcases"),
                    testcaseValidator,
                    deletionQueue,
                    testcaseWriter,
                    testcaseReader,
                    transactionalWriter,
                    currentActor,
                    courseAccess);
            submissionService = new SubmissionApplicationService(
                    problemService,
                    submissions,
                    caseResults,
                    jobs,
                    mock(AccountDirectory.class),
                    currentActor,
                    courseAccess);
        }

        private void linkToOutline() {
            when(courseOutlineLinks.isLinked(6L, CourseOutlineItemType.PROGRAMMING_PROBLEM, 1L)).thenReturn(true);
        }
    }
}
