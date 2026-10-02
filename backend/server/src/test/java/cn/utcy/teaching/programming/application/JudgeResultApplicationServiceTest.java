package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.programming.domain.JudgeJobStatus;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.domain.SubmissionCaseResult;
import cn.utcy.teaching.programming.domain.SubmissionStatus;
import cn.utcy.teaching.programming.infrastructure.JudgeJobMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import cn.utcy.teaching.programming.infrastructure.SubmissionCaseResultMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JudgeResultApplicationServiceTest {

    @Test
    void completesValidResultWhileHoldingJobRowLock() {
        String jobId = UUID.randomUUID().toString();
        JudgeJobMapper jobs = mock(JudgeJobMapper.class);
        ProgrammingSubmissionMapper submissions = mock(ProgrammingSubmissionMapper.class);
        SubmissionCaseResultMapper cases = mock(SubmissionCaseResultMapper.class);
        JudgeJob job = new JudgeJob(
                jobId,
                7L,
                1,
                JudgeJobStatus.PUBLISHED,
                "1-0",
                Instant.EPOCH,
                null,
                Instant.EPOCH);
        ProgrammingSubmission submission = new ProgrammingSubmission(
                7L,
                3L,
                11L,
                ProgrammingLanguage.CPP20,
                "int main(){}",
                SubmissionStatus.QUEUED,
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                null,
                Instant.EPOCH);
        when(jobs.selectForUpdate(jobId)).thenReturn(job);
        when(submissions.selectById(7L)).thenReturn(submission);
        when(cases.insert(any(SubmissionCaseResult.class))).thenReturn(1);
        when(submissions.updateById(any(ProgrammingSubmission.class))).thenReturn(1);
        when(jobs.updateById(any(JudgeJob.class))).thenReturn(1);
        JudgeResult result = new JudgeResult(
                1,
                jobId,
                1,
                7L,
                SubmissionStatus.ACCEPTED,
                10,
                1024,
                new BigDecimal("100.00"),
                "通过",
                List.of(new JudgeResult.CaseResult(
                        "case_1",
                        SubmissionStatus.ACCEPTED,
                        10,
                        1024,
                        new BigDecimal("100.00"),
                        "通过")));

        JudgeResultApplicationService.Completion completion = service(submissions, jobs, cases).complete(result);

        assertThat(completion.outcome()).isEqualTo(JudgeResultApplicationService.CompletionOutcome.APPLIED);
        assertThat(completion.jobStreamRecordId()).isEqualTo("1-0");
        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.ACCEPTED);
        assertThat(job.getStatus()).isEqualTo(JudgeJobStatus.COMPLETED);
        verify(jobs).selectForUpdate(jobId);
        verify(submissions).updateById(submission);
        verify(jobs).updateById(job);
    }

    @Test
    void zeroAffectedCaseInsertIsExplicitConflict() {
        CompletionFixture fixture = completionFixture();
        when(fixture.cases().insert(any(SubmissionCaseResult.class))).thenReturn(0);

        assertThatThrownBy(() -> fixture.service().complete(validResult(fixture.jobId())))
                .isInstanceOf(ConflictException.class)
                .hasMessage("测试点结果创建未生效");
    }

    @Test
    void zeroAffectedSubmissionUpdateIsExplicitConflict() {
        CompletionFixture fixture = completionFixture();
        when(fixture.submissions().updateById(any(ProgrammingSubmission.class))).thenReturn(0);

        assertThatThrownBy(() -> fixture.service().complete(validResult(fixture.jobId())))
                .isInstanceOf(ConflictException.class)
                .hasMessage("提交判题状态已变化，更新未生效");
    }

    @Test
    void zeroAffectedJobUpdateIsExplicitConflict() {
        CompletionFixture fixture = completionFixture();
        when(fixture.jobs().updateById(any(JudgeJob.class))).thenReturn(0);

        assertThatThrownBy(() -> fixture.service().complete(validResult(fixture.jobId())))
                .isInstanceOf(ConflictException.class)
                .hasMessage("判题任务状态已变化，更新未生效");
    }

    @Test
    void rejectsNegativeMetricsBeforeReadingDatabase() {
        JudgeResult result = result(
                -1,
                0,
                BigDecimal.ZERO,
                List.of());

        assertThatThrownBy(() -> service(
                mock(ProgrammingSubmissionMapper.class),
                mock(JudgeJobMapper.class),
                mock(SubmissionCaseResultMapper.class)).complete(result))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("判题结果的时间和内存不能为负数");
    }

    @Test
    void rejectsScoreThatDoesNotEqualCaseSum() {
        JudgeResult result = result(
                SubmissionStatus.WRONG_ANSWER,
                1,
                1,
                new BigDecimal("100"),
                List.of(new JudgeResult.CaseResult(
                        "case_1",
                        SubmissionStatus.WRONG_ANSWER,
                        1,
                        1,
                        new BigDecimal("50"),
                        "答案错误")));

        assertThatThrownBy(() -> service(
                mock(ProgrammingSubmissionMapper.class),
                mock(JudgeJobMapper.class),
                mock(SubmissionCaseResultMapper.class)).complete(result))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("判题总分必须等于各测试点得分之和");
    }

    @Test
    void rejectsAggregateTimeThatDoesNotEqualCaseSum() {
        JudgeResult result = result(
                SubmissionStatus.WRONG_ANSWER,
                9,
                8,
                new BigDecimal("50"),
                List.of(new JudgeResult.CaseResult(
                        "case_1",
                        SubmissionStatus.WRONG_ANSWER,
                        10,
                        8,
                        new BigDecimal("50"),
                        "答案错误")));

        assertThatThrownBy(() -> service(
                mock(ProgrammingSubmissionMapper.class),
                mock(JudgeJobMapper.class),
                mock(SubmissionCaseResultMapper.class)).complete(result))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("判题总耗时必须等于各测试点耗时之和");
    }

    @Test
    void rejectsAggregateMemoryThatIsNotMaximumCaseMemory() {
        JudgeResult result = result(
                SubmissionStatus.WRONG_ANSWER,
                10,
                7,
                new BigDecimal("50"),
                List.of(new JudgeResult.CaseResult(
                        "case_1",
                        SubmissionStatus.WRONG_ANSWER,
                        10,
                        8,
                        new BigDecimal("50"),
                        "答案错误")));

        assertThatThrownBy(() -> service(
                mock(ProgrammingSubmissionMapper.class),
                mock(JudgeJobMapper.class),
                mock(SubmissionCaseResultMapper.class)).complete(result))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("判题总内存必须等于各测试点内存最大值");
    }

    @Test
    void rejectsCasesForCompileError() {
        JudgeResult result = result(
                SubmissionStatus.COMPILE_ERROR,
                1,
                0,
                BigDecimal.ZERO,
                List.of(new JudgeResult.CaseResult(
                        "case_1",
                        SubmissionStatus.COMPILE_ERROR,
                        1,
                        0,
                        BigDecimal.ZERO,
                        "编译错误")));

        assertThatThrownBy(() -> service(
                mock(ProgrammingSubmissionMapper.class),
                mock(JudgeJobMapper.class),
                mock(SubmissionCaseResultMapper.class)).complete(result))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("非测试点终态必须不含测试点且总时间、内存和得分均为 0");
    }

    @Test
    void rejectsEmptyCasesForCaseTerminalStatus() {
        JudgeResult result = result(
                SubmissionStatus.WRONG_ANSWER,
                0,
                0,
                BigDecimal.ZERO,
                List.of());

        assertThatThrownBy(() -> service(
                mock(ProgrammingSubmissionMapper.class),
                mock(JudgeJobMapper.class),
                mock(SubmissionCaseResultMapper.class)).complete(result))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("测试点终态必须至少包含一个测试点结果");
    }

    @Test
    void rejectsNonCaseStatusInsideCases() {
        JudgeResult result = result(
                SubmissionStatus.WRONG_ANSWER,
                0,
                0,
                BigDecimal.ZERO,
                List.of(new JudgeResult.CaseResult(
                        "case_1",
                        SubmissionStatus.SYSTEM_ERROR,
                        0,
                        0,
                        BigDecimal.ZERO,
                        "系统错误")));

        assertThatThrownBy(() -> service(
                mock(ProgrammingSubmissionMapper.class),
                mock(JudgeJobMapper.class),
                mock(SubmissionCaseResultMapper.class)).complete(result))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("测试点结果缺少必填字段或状态不属于测试点终态");
    }

    @Test
    void rejectsOverallStatusDifferentFromFirstNonAcceptedCase() {
        JudgeResult result = result(
                SubmissionStatus.RUNTIME_ERROR,
                2,
                8,
                new BigDecimal("50.00"),
                List.of(
                        new JudgeResult.CaseResult(
                                "case_1",
                                SubmissionStatus.ACCEPTED,
                                1,
                                4,
                                new BigDecimal("50.00"),
                                "通过"),
                        new JudgeResult.CaseResult(
                                "case_2",
                                SubmissionStatus.WRONG_ANSWER,
                                1,
                                8,
                                BigDecimal.ZERO,
                                "答案错误")));

        assertThatThrownBy(() -> service(
                mock(ProgrammingSubmissionMapper.class),
                mock(JudgeJobMapper.class),
                mock(SubmissionCaseResultMapper.class)).complete(result))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("判题总状态必须等于第一个未通过测试点的状态，全部通过时必须为 ACCEPTED");
    }

    private JudgeResult result(
            int timeUsedMs,
            int memoryUsedKb,
            BigDecimal score,
            List<JudgeResult.CaseResult> cases
    ) {
        return new JudgeResult(
                1,
                UUID.randomUUID().toString(),
                1,
                7L,
                SubmissionStatus.SYSTEM_ERROR,
                timeUsedMs,
                memoryUsedKb,
                score,
                "系统错误",
                cases);
    }

    @Test
    void staleAttemptResultIsDiscardedWithoutAnyWrite() {
        CompletionFixture fixture = completionFixture();
        JudgeResult stale = validResult(fixture.jobId());
        stale = new JudgeResult(stale.schemaVersion(), stale.jobId(), stale.attempt() + 1, stale.submissionId(),
                stale.status(), stale.timeUsedMs(), stale.memoryUsedKb(), stale.score(), stale.detail(), stale.cases());

        JudgeResultApplicationService.Completion completion = fixture.service().complete(stale);

        assertThat(completion.outcome()).isEqualTo(JudgeResultApplicationService.CompletionOutcome.STALE_ATTEMPT);
        verify(fixture.submissions(), org.mockito.Mockito.never()).updateById(any(ProgrammingSubmission.class));
        verify(fixture.jobs(), org.mockito.Mockito.never()).updateById(any(JudgeJob.class));
    }

    @Test
    void missingJobResultIsDiscardedAsBenign() {
        CompletionFixture fixture = completionFixture();
        String unknownJob = UUID.randomUUID().toString();
        JudgeResult orphan = validResult(fixture.jobId());
        orphan = new JudgeResult(orphan.schemaVersion(), unknownJob, orphan.attempt(), orphan.submissionId(),
                orphan.status(), orphan.timeUsedMs(), orphan.memoryUsedKb(), orphan.score(), orphan.detail(),
                orphan.cases());

        JudgeResultApplicationService.Completion completion = fixture.service().complete(orphan);

        assertThat(completion.outcome()).isEqualTo(JudgeResultApplicationService.CompletionOutcome.JOB_MISSING);
        verify(fixture.submissions(), org.mockito.Mockito.never()).updateById(any(ProgrammingSubmission.class));
    }

    private CompletionFixture completionFixture() {
        String jobId = UUID.randomUUID().toString();
        JudgeJobMapper jobs = mock(JudgeJobMapper.class);
        ProgrammingSubmissionMapper submissions = mock(ProgrammingSubmissionMapper.class);
        SubmissionCaseResultMapper cases = mock(SubmissionCaseResultMapper.class);
        when(jobs.selectForUpdate(jobId)).thenReturn(new JudgeJob(
                jobId,
                7L,
                1,
                JudgeJobStatus.PUBLISHED,
                "1-0",
                Instant.EPOCH,
                null,
                Instant.EPOCH));
        when(submissions.selectById(7L)).thenReturn(new ProgrammingSubmission(
                7L,
                3L,
                11L,
                ProgrammingLanguage.CPP20,
                "int main(){}",
                SubmissionStatus.QUEUED,
                null,
                null,
                null,
                null,
                Instant.EPOCH,
                null,
                Instant.EPOCH));
        when(cases.insert(any(SubmissionCaseResult.class))).thenReturn(1);
        when(submissions.updateById(any(ProgrammingSubmission.class))).thenReturn(1);
        when(jobs.updateById(any(JudgeJob.class))).thenReturn(1);
        return new CompletionFixture(
                jobId,
                submissions,
                jobs,
                cases,
                service(submissions, jobs, cases));
    }

    private JudgeResult validResult(String jobId) {
        return new JudgeResult(
                1,
                jobId,
                1,
                7L,
                SubmissionStatus.ACCEPTED,
                10,
                1024,
                new BigDecimal("100.00"),
                "通过",
                List.of(new JudgeResult.CaseResult(
                        "case_1",
                        SubmissionStatus.ACCEPTED,
                        10,
                        1024,
                        new BigDecimal("100.00"),
                        "通过")));
    }

    private JudgeResult result(
            SubmissionStatus status,
            int timeUsedMs,
            int memoryUsedKb,
            BigDecimal score,
            List<JudgeResult.CaseResult> cases
    ) {
        return new JudgeResult(
                1,
                UUID.randomUUID().toString(),
                1,
                7L,
                status,
                timeUsedMs,
                memoryUsedKb,
                score,
                "系统错误",
                cases);
    }

    private JudgeResultApplicationService service(
            ProgrammingSubmissionMapper submissions,
            JudgeJobMapper jobs,
            SubmissionCaseResultMapper cases
    ) {
        return new JudgeResultApplicationService(submissions, jobs, cases, mock(ProgrammingProblemMapper.class), mock(LearningEventRecorder.class));
    }

    private record CompletionFixture(
            String jobId,
            ProgrammingSubmissionMapper submissions,
            JudgeJobMapper jobs,
            SubmissionCaseResultMapper cases,
            JudgeResultApplicationService service
    ) {
    }
}
