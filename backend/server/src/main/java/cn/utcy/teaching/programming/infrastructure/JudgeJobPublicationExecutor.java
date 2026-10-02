package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.programming.domain.JudgeJobStatus;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.domain.SubmissionStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 单个判题作业的发布:独立事务,一条失败不影响同批其它作业(发布不允许队头阻塞)。
 * XADD 经 Lua 幂等(键 jobId:attempt),行内失败后重发会拿到同一 recordId 并收敛。
 */
@Component
class JudgeJobPublicationExecutor {

    private static final Logger log = LoggerFactory.getLogger(JudgeJobPublicationExecutor.class);
    private static final int SCHEMA_VERSION = 1;
    private static final DefaultRedisScript<String> PUBLISH_SCRIPT =
            new DefaultRedisScript<>("""
                    local existing = redis.call('HGET', KEYS[2], ARGV[1])
                    if existing then
                        return existing
                    end
                    local recordId = redis.call('XADD', KEYS[1], '*', 'payload', ARGV[2])
                    redis.call('HSET', KEYS[2], ARGV[1], recordId)
                    return recordId
                    """, String.class);

    private final JudgeJobMapper jobs;
    private final ProgrammingSubmissionMapper submissions;
    private final ProgrammingProblemMapper problems;
    private final StringRedisTemplate redis;
    private final JudgeProperties properties;
    private final ObjectMapper objectMapper;

    JudgeJobPublicationExecutor(
            JudgeJobMapper jobs,
            ProgrammingSubmissionMapper submissions,
            ProgrammingProblemMapper problems,
            StringRedisTemplate redis,
            JudgeProperties properties,
            ObjectMapper objectMapper
    ) {
        this.jobs = jobs;
        this.submissions = submissions;
        this.problems = problems;
        this.redis = redis;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishOne(String jobId) {
        JudgeJob job = jobs.selectForUpdate(jobId);
        if (job == null || job.getStatus() != JudgeJobStatus.PENDING) {
            return;
        }
        ProgrammingSubmission submission = submissions.selectById(job.getSubmissionId());
        if (submission == null) {
            // 提交已不存在(删除竞态):作业失去意义,直接移除
            jobs.deleteById(jobId);
            log.warn("判题作业引用的提交已不存在，作业已移除：{}", jobId);
            return;
        }
        ProgrammingProblem problem = problems.selectById(submission.getProblemId());
        if (problem == null) {
            terminalize(job, submission, "判题任务数据不完整：题目已不存在");
            return;
        }
        JobPayload payload = new JobPayload(
                SCHEMA_VERSION,
                job.getId(),
                job.getAttempt(),
                submission.getId(),
                problem.getId(),
                problem.getTestcaseSha256(),
                submission.getLanguage(),
                submission.getSourceCode(),
                new Limits(problem.getTimeLimitMs(), problem.getMemoryLimitMb(), problem.getOutputLimitKb()));
        String publicationKey = publicationKey(job.getId(), job.getAttempt());
        String recordId = redis.execute(
                PUBLISH_SCRIPT,
                List.of(properties.jobsStream(), publicationIndexKey()),
                publicationKey,
                serialize(payload));
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalStateException("Redis 未返回判题任务记录 ID");
        }
        int updated = jobs.markPublished(job.getId(), recordId, Instant.now());
        if (updated == 0) {
            JudgeJob current = jobs.selectById(job.getId());
            if (current == null) {
                throw new IllegalStateException("判题作业在发布过程中被删除：" + job.getId());
            }
            if (current.getStatus() != JudgeJobStatus.PUBLISHED
                    || !Objects.equals(current.getStreamRecordId(), recordId)) {
                throw new IllegalStateException(
                        "判题作业发布状态与本次 Redis 记录不一致：" + job.getId());
            }
        }
    }

    private void terminalize(JudgeJob job, ProgrammingSubmission submission, String detail) {
        if (!submission.getStatus().terminal()) {
            submission.complete(SubmissionStatus.SYSTEM_ERROR, null, null, null, detail);
            submissions.updateById(submission);
        }
        job.completed();
        jobs.updateById(job);
        log.error("判题作业数据不完整，提交已置系统错误：jobId={}，submissionId={}", job.getId(), submission.getId());
    }

    private String publicationIndexKey() {
        return properties.jobsStream() + ".published";
    }

    private String publicationKey(String jobId, int attempt) {
        return jobId + ":" + attempt;
    }

    private String serialize(JobPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("判题任务无法序列化为约定 JSON", exception);
        }
    }

    record JobPayload(
            int schemaVersion,
            String jobId,
            int attempt,
            long submissionId,
            long problemId,
            String testcaseSha256,
            cn.utcy.teaching.programming.domain.ProgrammingLanguage language,
            String sourceCode,
            Limits limits
    ) {
    }

    record Limits(int timeLimitMs, int memoryLimitMb, int outputLimitKb) {
    }
}
