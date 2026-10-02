package cn.utcy.teaching.programming.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("judge_job")
public class JudgeJob {

    @TableId(type = IdType.INPUT)
    private String id;
    private Long submissionId;
    private int attempt;
    private int requeueCount;
    private JudgeJobStatus status;
    private String streamRecordId;
    private Instant publishedAt;
    private Instant completedAt;
    private Instant createdAt;

    protected JudgeJob() {
    }

    public JudgeJob(String id, Long submissionId, int attempt, JudgeJobStatus status, String streamRecordId,
                    Instant publishedAt, Instant completedAt, Instant createdAt) {
        this.id = id;
        this.submissionId = submissionId;
        this.attempt = attempt;
        this.requeueCount = 0;
        this.status = status;
        this.streamRecordId = streamRecordId;
        this.publishedAt = publishedAt;
        this.completedAt = completedAt;
        this.createdAt = createdAt;
    }

    public static JudgeJob pending(long submissionId) {
        return new JudgeJob(UUID.randomUUID().toString(), submissionId, 1,
                JudgeJobStatus.PENDING, null, null, null, Instant.now());
    }

    public void completed() {
        this.status = JudgeJobStatus.COMPLETED;
        this.completedAt = Instant.now();
    }

    /** 对账重投:换下一个执行次数重新排队(attempt 即结果侧的防闪回围栏) */
    public void requeue() {
        this.status = JudgeJobStatus.PENDING;
        this.attempt = this.attempt + 1;
        this.requeueCount = this.requeueCount + 1;
        this.streamRecordId = null;
        this.publishedAt = null;
        this.completedAt = null;
    }

    /** 管理员重判:同样换执行次数,但重投计数清零(人工介入即重新起算) */
    public void rejudge() {
        requeue();
        this.requeueCount = 0;
    }

    public int getRequeueCount() {
        return requeueCount;
    }

    public String getId() {
        return id;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public int getAttempt() {
        return attempt;
    }

    public JudgeJobStatus getStatus() {
        return status;
    }

    public String getStreamRecordId() {
        return streamRecordId;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
