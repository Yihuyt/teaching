package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.programming.domain.JudgeJobStatus;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.domain.SubmissionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JudgeJobReconcilerTest {
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final String JOB_ID = "b88ccbba-9cca-49e7-8435-3d2313eb066a";

    @Test
    @SuppressWarnings("unchecked")
    void publishedTimeoutReclaimsOldDeliveryAndRequeuesNextAttempt() {
        Fixture fixture = fixture();
        JudgeJob job = publishedJob(NOW.minus(Duration.ofMinutes(11)));
        when(fixture.jobs().selectList(any())).thenReturn(List.of(job)).thenReturn(List.of());
        when(fixture.jobs().selectForUpdate(JOB_ID)).thenReturn(job);
        when(fixture.jobs().resetForRepublish(JOB_ID, 2, 1, "published")).thenReturn(1);

        fixture.reconciler().reconcile();

        verify(fixture.reclaimer()).reclaim("9-0", JOB_ID + ":1");
        verify(fixture.jobs()).resetForRepublish(JOB_ID, 2, 1, "published");
    }

    @Test
    @SuppressWarnings("unchecked")
    void requeueLimitTerminalizesTheSubmission() {
        Fixture fixture = fixture();
        JudgeJob job = publishedJob(NOW.minus(Duration.ofMinutes(11)));
        ReflectionTestUtils.setField(job, "requeueCount", 3);
        ProgrammingSubmission submission = submission();
        when(fixture.jobs().selectList(any())).thenReturn(List.of(job)).thenReturn(List.of());
        when(fixture.jobs().selectForUpdate(JOB_ID)).thenReturn(job);
        when(fixture.submissions().selectById(7L)).thenReturn(submission);
        when(fixture.submissions().updateById(any(ProgrammingSubmission.class))).thenReturn(1);
        when(fixture.jobs().updateById(any(JudgeJob.class))).thenReturn(1);

        fixture.reconciler().reconcile();

        assertThat(submission.getStatus()).isEqualTo(SubmissionStatus.SYSTEM_ERROR);
        assertThat(submission.getResultDetail()).isEqualTo("判题系统多次投递未收到结果，请联系管理员重判");
        assertThat(job.getStatus()).isEqualTo(JudgeJobStatus.COMPLETED);
        verify(fixture.jobs(), never()).resetForRepublish(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void stuckPendingJobTerminalizesTheSubmission() {
        Fixture fixture = fixture();
        JudgeJob job = new JudgeJob(JOB_ID, 7L, 1, JudgeJobStatus.PENDING, null, null, null,
                NOW.minus(Duration.ofMinutes(16)));
        ProgrammingSubmission submission = submission();
        when(fixture.jobs().selectList(any())).thenReturn(List.of()).thenReturn(List.of(job));
        when(fixture.jobs().selectForUpdate(JOB_ID)).thenReturn(job);
        when(fixture.submissions().selectById(7L)).thenReturn(submission);
        when(fixture.submissions().updateById(any(ProgrammingSubmission.class))).thenReturn(1);
        when(fixture.jobs().updateById(any(JudgeJob.class))).thenReturn(1);

        fixture.reconciler().reconcile();

        assertThat(submission.getResultDetail()).isEqualTo("判题任务长时间未能发布，请联系管理员重判");
        assertThat(job.getStatus()).isEqualTo(JudgeJobStatus.COMPLETED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void lockedRecheckSkipsJobsAlreadyHandledConcurrently() {
        Fixture fixture = fixture();
        JudgeJob stale = publishedJob(NOW.minus(Duration.ofMinutes(11)));
        JudgeJob current = publishedJob(NOW.minus(Duration.ofMinutes(11)));
        current.completed();
        when(fixture.jobs().selectList(any())).thenReturn(List.of(stale)).thenReturn(List.of());
        when(fixture.jobs().selectForUpdate(JOB_ID)).thenReturn(current);

        fixture.reconciler().reconcile();

        verify(fixture.reclaimer(), never()).reclaim(anyString(), anyString());
        verify(fixture.jobs(), never()).resetForRepublish(anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), anyString());
    }

    private static JudgeJob publishedJob(Instant publishedAt) {
        return new JudgeJob(JOB_ID, 7L, 1, JudgeJobStatus.PUBLISHED, "9-0", publishedAt, null,
                publishedAt.minus(Duration.ofSeconds(1)));
    }

    private static ProgrammingSubmission submission() {
        return new ProgrammingSubmission(7L, 3L, 11L, ProgrammingLanguage.CPP20, "int main(){}",
                SubmissionStatus.QUEUED, null, null, null, null, NOW.minus(Duration.ofMinutes(20)), null,
                NOW.minus(Duration.ofMinutes(20)));
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() {
        JudgeJobMapper jobs = mock(JudgeJobMapper.class);
        ProgrammingSubmissionMapper submissions = mock(ProgrammingSubmissionMapper.class);
        JudgeStreamReclaimer reclaimer = mock(JudgeStreamReclaimer.class);
        JudgeJobReconciler reconciler = new JudgeJobReconciler(
                jobs,
                submissions,
                reclaimer,
                new JudgeProperties("judge.jobs", "judge.results", "backend", "judge-workers",
                        Duration.ofMinutes(10), Duration.ofMinutes(15), 3),
                new TransactionTemplate(mock(PlatformTransactionManager.class)),
                Clock.fixed(NOW, ZoneOffset.UTC), mock(JudgeWorkerRegistry.class));
        return new Fixture(reconciler, jobs, submissions, reclaimer);
    }

    private record Fixture(
            JudgeJobReconciler reconciler,
            JudgeJobMapper jobs,
            ProgrammingSubmissionMapper submissions,
            JudgeStreamReclaimer reclaimer
    ) {
    }
}
