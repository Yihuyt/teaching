package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.application.JudgeResult;
import cn.utcy.teaching.programming.application.JudgeResultApplicationService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JudgeResultListenerTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void parsesStrictResultPayload() {
        String jobId = UUID.randomUUID().toString();
        JudgeResult result = listener().parse("""
                {
                  "schemaVersion": 1,
                  "jobId": "%s",
                  "attempt": 1,
                  "submissionId": 9,
                  "status": "ACCEPTED",
                  "timeUsedMs": 10,
                  "memoryUsedKb": 1024,
                  "score": 100,
                  "detail": "通过",
                  "cases": [
                    {
                      "caseId": "case_1",
                      "status": "ACCEPTED",
                      "timeUsedMs": 10,
                      "memoryUsedKb": 1024,
                      "score": 100,
                      "detail": "通过"
                    }
                  ]
                }
                """.formatted(jobId));

        assertThat(result.jobId()).isEqualTo(jobId);
        assertThat(result.cases()).hasSize(1);
    }

    @Test
    void rejectsMissingPrimitiveFieldInsteadOfInventingDefaultValue() {
        assertThatIllegalStateException()
                .isThrownBy(() -> listener().parse("""
                        {
                          "schemaVersion": 1,
                          "jobId": "%s",
                          "attempt": 1,
                          "submissionId": 9,
                          "status": "SYSTEM_ERROR",
                          "memoryUsedKb": 0,
                          "score": 0,
                          "detail": "错误",
                          "cases": []
                        }
                        """.formatted(UUID.randomUUID())))
                .withMessage("判题结果字段不符合 schemaVersion=1 严格结构");
    }

    @Test
    @SuppressWarnings("unchecked")
    void poisonResultIsIsolatedAndPipelineAdvances() {
        PipelineFixture fixture = pipelineFixture();
        when(fixture.resultService().complete(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new cn.utcy.teaching.shared.error.ConflictException("同一提交收到了相互冲突的判题终态"))
                .thenReturn(new JudgeResultApplicationService.Completion(
                        JudgeResultApplicationService.CompletionOutcome.APPLIED, "1-0"));

        fixture.listener().receiveResults();

        // 第一条隔离进死信、第二条正常应用,两条都推进
        org.mockito.Mockito.verify(fixture.deadLetters()).record(
                org.mockito.ArgumentMatchers.eq("5-0"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(RuntimeException.class));
        org.mockito.Mockito.verify(fixture.redis(), org.mockito.Mockito.times(2)).execute(
                org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.DefaultRedisScript.class),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void transientFailureStopsWithoutAdvancing() {
        PipelineFixture fixture = pipelineFixture();
        when(fixture.resultService().complete(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("数据库超时"));

        fixture.listener().receiveResults();

        org.mockito.Mockito.verify(fixture.deadLetters(), org.mockito.Mockito.never()).record(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(RuntimeException.class));
        org.mockito.Mockito.verify(fixture.redis(), org.mockito.Mockito.never()).execute(
                org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.DefaultRedisScript.class),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void benignOutcomesAdvanceWithoutDeadLetter() {
        PipelineFixture fixture = pipelineFixture();
        when(fixture.resultService().complete(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new JudgeResultApplicationService.Completion(
                        JudgeResultApplicationService.CompletionOutcome.JOB_MISSING, null))
                .thenReturn(new JudgeResultApplicationService.Completion(
                        JudgeResultApplicationService.CompletionOutcome.STALE_ATTEMPT, null));

        fixture.listener().receiveResults();

        org.mockito.Mockito.verify(fixture.deadLetters(), org.mockito.Mockito.never()).record(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(RuntimeException.class));
        org.mockito.Mockito.verify(fixture.redis(), org.mockito.Mockito.times(2)).execute(
                org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.DefaultRedisScript.class),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @SuppressWarnings("unchecked")
    private PipelineFixture pipelineFixture() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> values =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        org.springframework.data.redis.core.StreamOperations<String, Object, Object> streams =
                mock(org.springframework.data.redis.core.StreamOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForStream()).thenReturn(streams);
        when(values.get(org.mockito.ArgumentMatchers.anyString())).thenReturn(null);
        when(streams.read(
                org.mockito.ArgumentMatchers.any(org.springframework.data.redis.connection.stream.StreamReadOptions.class),
                org.mockito.ArgumentMatchers.any(org.springframework.data.redis.connection.stream.StreamOffset.class)))
                .thenReturn(java.util.List.of(record("5-0"), record("6-0")));
        when(redis.execute(
                org.mockito.ArgumentMatchers.any(org.springframework.data.redis.core.script.DefaultRedisScript.class),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn("OK");
        JudgeResultApplicationService resultService = mock(JudgeResultApplicationService.class);
        JudgeResultDeadLetterStore deadLetters = mock(JudgeResultDeadLetterStore.class);
        JudgeResultListener listener = new JudgeResultListener(
                redis,
                new JudgeProperties("judge.jobs", "judge.results", "test", "judge-workers", java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(15), 3),
                resultService,
                deadLetters,
                new JudgeResultPipelineHealthIndicator(java.time.Clock.systemUTC()),
                objectMapper);
        return new PipelineFixture(listener, redis, resultService, deadLetters);
    }

    private org.springframework.data.redis.connection.stream.MapRecord<String, Object, Object> record(String id) {
        String payload = """
                {
                  "schemaVersion": 1,
                  "jobId": "%s",
                  "attempt": 1,
                  "submissionId": 9,
                  "status": "SYSTEM_ERROR",
                  "timeUsedMs": 0,
                  "memoryUsedKb": 0,
                  "score": 0,
                  "detail": "错误",
                  "cases": []
                }
                """.formatted(UUID.randomUUID());
        return org.springframework.data.redis.connection.stream.StreamRecords.newRecord()
                .in("judge.results")
                .withId(org.springframework.data.redis.connection.stream.RecordId.of(id))
                .ofMap(java.util.Map.of("payload", payload));
    }

    private record PipelineFixture(
            JudgeResultListener listener,
            StringRedisTemplate redis,
            JudgeResultApplicationService resultService,
            JudgeResultDeadLetterStore deadLetters
    ) {
    }

    private JudgeResultListener listener() {
        return new JudgeResultListener(
                mock(StringRedisTemplate.class),
                new JudgeProperties("judge.jobs", "judge.results", "test", "judge-workers", java.time.Duration.ofMinutes(10), java.time.Duration.ofMinutes(15), 3),
                mock(JudgeResultApplicationService.class),
                mock(JudgeResultDeadLetterStore.class),
                new JudgeResultPipelineHealthIndicator(java.time.Clock.systemUTC()),
                objectMapper);
    }
}
