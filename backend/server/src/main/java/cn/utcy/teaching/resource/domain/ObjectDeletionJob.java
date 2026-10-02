package cn.utcy.teaching.resource.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.util.UUID;

@TableName("object_deletion_job")
public class ObjectDeletionJob {

    @TableId
    private String id;
    private String bucketName;
    private String objectKey;
    private ObjectDeletionJobStatus status;
    private int attempts;
    // 状态迁移要把下列可空列写回 NULL(如 completed 必须清空 next_attempt_at,
    // 由 ck_object_deletion_completion 强制);updateById 默认策略跳过 null 字段,
    // 会漏写这些列导致约束违反、事务回滚、任务卡死,故显式 ALWAYS。
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant nextAttemptAt;
    private Instant createdAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant completedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Instant failedAt;

    protected ObjectDeletionJob() {
    }

    public ObjectDeletionJob(
            String id,
            String bucketName,
            String objectKey,
            ObjectDeletionJobStatus status,
            int attempts,
            String lastError,
            Instant nextAttemptAt,
            Instant createdAt,
            Instant completedAt,
            Instant failedAt
    ) {
        this.id = id;
        this.bucketName = bucketName;
        this.objectKey = objectKey;
        this.status = status;
        this.attempts = attempts;
        this.lastError = lastError;
        this.nextAttemptAt = nextAttemptAt;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
        this.failedAt = failedAt;
    }

    public static ObjectDeletionJob pending(String bucketName, String objectKey) {
        return new ObjectDeletionJob(
                UUID.randomUUID().toString(),
                bucketName,
                objectKey,
                ObjectDeletionJobStatus.PENDING,
                0,
                null,
                Instant.now(),
                Instant.now(),
                null,
                null);
    }

    public void complete() {
        status = ObjectDeletionJobStatus.COMPLETED;
        attempts++;
        lastError = null;
        nextAttemptAt = null;
        completedAt = Instant.now();
    }

    public void recordFailure(String error, int maximumAttempts, Instant failedAt) {
        attempts++;
        lastError = error;
        completedAt = null;
        if (attempts >= maximumAttempts) {
            status = ObjectDeletionJobStatus.FAILED;
            nextAttemptAt = null;
            this.failedAt = failedAt;
            return;
        }
        long delaySeconds = 10L << (attempts - 1);
        nextAttemptAt = failedAt.plusSeconds(delaySeconds);
    }

    public String getId() { return id; }
    public String getBucketName() { return bucketName; }
    public String getObjectKey() { return objectKey; }
    public ObjectDeletionJobStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getFailedAt() { return failedAt; }
}
