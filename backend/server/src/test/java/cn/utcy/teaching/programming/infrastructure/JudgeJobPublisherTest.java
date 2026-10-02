package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.programming.domain.JudgeJobStatus;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.domain.SubmissionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JudgeJobPublisherTest {

    private static final String JOB_ID = "b88ccbba-9cca-49e7-8435-3d2313eb066a";

    @Test
    void retryUsesTheSameJobAttemptIndexAndRedisRecordId() {
        ExecutorFixture fixture = executorFixture(pendingJob(), null);
        when(fixture.jobs().markPublished(eq(JOB_ID), eq("1-0"), any(Instant.class))).thenReturn(1);

        fixture.executor().publishOne(JOB_ID);
        fixture.executor().publishOne(JOB_ID);

        verify(fixture.redis(), times(2)).execute(
                any(DefaultRedisScript.class),
                eq(List.of("judge.jobs", "judge.jobs.published")),
                eq(JOB_ID + ":2"),
                anyString());
        verify(fixture.jobs(), times(2)).markPublished(eq(JOB_ID), eq("1-0"), any(Instant.class));
    }

    @Test
    void zeroRowUpdateIsIdempotentOnlyForTheSamePublishedRecord() {
        ExecutorFixture fixture = executorFixture(pendingJob(), new JudgeJob(
                JOB_ID, 7L, 2, JudgeJobStatus.PUBLISHED, "1-0", Instant.EPOCH, null, Instant.EPOCH));

        assertThatCode(() -> fixture.executor().publishOne(JOB_ID)).doesNotThrowAnyException();
    }

    @Test
    void zeroRowUpdateCannotHideAPendingDatabaseRecord() {
        ExecutorFixture fixture = executorFixture(pendingJob(), pendingJob());

        assertThatThrownBy(() -> fixture.executor().publishOne(JOB_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("判题作业发布状态与本次 Redis 记录不一致：" + JOB_ID);
    }

    @Test
    void zeroRowUpdateCannotAcceptAnotherRedisRecord() {
        ExecutorFixture fixture = executorFixture(pendingJob(), new JudgeJob(
                JOB_ID, 7L, 2, JudgeJobStatus.PUBLISHED, "2-0", Instant.EPOCH, null, Instant.EPOCH));

        assertThatThrownBy(() -> fixture.executor().publishOne(JOB_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("判题作业发布状态与本次 Redis 记录不一致：" + JOB_ID);
    }

    @Test
    void missingSubmissionRemovesTheOrphanJob() {
        ExecutorFixture fixture = executorFixture(pendingJob(), null);
        when(fixture.submissions().selectById(7L)).thenReturn(null);

        fixture.executor().publishOne(JOB_ID);

        verify(fixture.jobs()).deleteById(JOB_ID);
        verify(fixture.redis(), never()).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString());
    }

    @Test
    void missingProblemTerminalizesTheSubmissionInsteadOfBlocking() {
        ExecutorFixture fixture = executorFixture(pendingJob(), null);
        when(fixture.problems().selectById(3L)).thenReturn(null);
        when(fixture.submission().getStatus()).thenReturn(SubmissionStatus.QUEUED);

        fixture.executor().publishOne(JOB_ID);

        verify(fixture.submission()).complete(
                eq(SubmissionStatus.SYSTEM_ERROR), isNull(), isNull(), isNull(), eq("判题任务数据不完整：题目已不存在"));
        verify(fixture.submissions()).updateById(fixture.submission());
        verify(fixture.jobs()).updateById(any(JudgeJob.class));
        verify(fixture.redis(), never()).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString());
    }

    @Test
    void oneFailingJobDoesNotBlockTheRestOfTheBatch() {
        JudgeJobMapper jobs = mock(JudgeJobMapper.class);
        JudgeJobPublicationExecutor executor = mock(JudgeJobPublicationExecutor.class);
        JudgeJob bad = new JudgeJob("bad", 1L, 1, JudgeJobStatus.PENDING, null, null, null, Instant.EPOCH);
        JudgeJob good = new JudgeJob("good", 2L, 1, JudgeJobStatus.PENDING, null, null, null, Instant.EPOCH);
        when(jobs.selectList(any())).thenReturn(List.of(bad, good));
        doThrow(new IllegalStateException("Redis 未返回判题任务记录 ID")).when(executor).publishOne("bad");

        JudgeJobPublisher publisher = new JudgeJobPublisher(jobs, executor);
        assertThatCode(publisher::publishPendingJobs).doesNotThrowAnyException();

        verify(executor).publishOne("bad");
        verify(executor).publishOne("good");
    }

    private static JudgeJob pendingJob() {
        return new JudgeJob(JOB_ID, 7L, 2, JudgeJobStatus.PENDING, null, null, null, Instant.EPOCH);
    }

    /** currentAfterZeroRowUpdate 为 null 表示 markPublished 正常返回 1 的场景由用例自行覆盖 */
    private ExecutorFixture executorFixture(JudgeJob locked, JudgeJob currentAfterZeroRowUpdate) {
        JudgeJobMapper jobs = mock(JudgeJobMapper.class);
        ProgrammingSubmissionMapper submissions = mock(ProgrammingSubmissionMapper.class);
        ProgrammingProblemMapper problems = mock(ProgrammingProblemMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ProgrammingSubmission submission = mock(ProgrammingSubmission.class);
        ProgrammingProblem problem = mock(ProgrammingProblem.class);
        when(jobs.selectForUpdate(JOB_ID)).thenReturn(locked);
        if (currentAfterZeroRowUpdate != null) {
            when(jobs.markPublished(eq(JOB_ID), eq("1-0"), any(Instant.class))).thenReturn(0);
            when(jobs.selectById(JOB_ID)).thenReturn(currentAfterZeroRowUpdate);
        }
        when(submissions.selectById(7L)).thenReturn(submission);
        when(submission.getId()).thenReturn(7L);
        when(submission.getProblemId()).thenReturn(3L);
        when(submission.getLanguage()).thenReturn(ProgrammingLanguage.CPP20);
        when(submission.getSourceCode()).thenReturn("int main(){}");
        when(problems.selectById(3L)).thenReturn(problem);
        when(problem.getId()).thenReturn(3L);
        when(problem.getTestcaseSha256()).thenReturn("a".repeat(64));
        when(problem.getTimeLimitMs()).thenReturn(1000);
        when(problem.getMemoryLimitMb()).thenReturn(256);
        when(problem.getOutputLimitKb()).thenReturn(1024);
        when(redis.execute(
                any(DefaultRedisScript.class),
                anyList(),
                anyString(),
                anyString())).thenReturn("1-0");
        return new ExecutorFixture(new JudgeJobPublicationExecutor(
                jobs,
                submissions,
                problems,
                redis,
                new JudgeProperties("judge.jobs", "judge.results", "backend", "judge-workers", java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(15), 3),
                new ObjectMapper()), jobs, submissions, problems, redis, submission);
    }

    private record ExecutorFixture(
            JudgeJobPublicationExecutor executor,
            JudgeJobMapper jobs,
            ProgrammingSubmissionMapper submissions,
            ProgrammingProblemMapper problems,
            StringRedisTemplate redis,
            ProgrammingSubmission submission
    ) {
    }
}
