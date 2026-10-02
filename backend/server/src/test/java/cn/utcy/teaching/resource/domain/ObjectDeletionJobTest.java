package cn.utcy.teaching.resource.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectDeletionJobTest {

    @Test
    void failureSchedulesExponentialBackoffBeforeMaximumAttempt() {
        ObjectDeletionJob job = ObjectDeletionJob.pending("bucket", "object");
        Instant failedAt = Instant.parse("2026-07-23T00:00:00Z");

        job.recordFailure("network", 5, failedAt);

        assertThat(job.getStatus()).isEqualTo(ObjectDeletionJobStatus.PENDING);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(job.getNextAttemptAt()).isEqualTo(failedAt.plusSeconds(10));
        assertThat(job.getFailedAt()).isNull();
    }

    @Test
    void fifthFailureTerminatesJobAsObservableFailure() {
        ObjectDeletionJob job = ObjectDeletionJob.pending("bucket", "object");
        Instant failedAt = Instant.parse("2026-07-23T00:00:00Z");
        for (int attempt = 0; attempt < 5; attempt++) {
            job.recordFailure("network", 5, failedAt.plusSeconds(attempt));
        }

        assertThat(job.getStatus()).isEqualTo(ObjectDeletionJobStatus.FAILED);
        assertThat(job.getAttempts()).isEqualTo(5);
        assertThat(job.getNextAttemptAt()).isNull();
        assertThat(job.getFailedAt()).isEqualTo(failedAt.plusSeconds(4));
        assertThat(job.getLastError()).isEqualTo("network");
    }

    /**
     * 状态迁移把可空列写回 NULL(completed 清 next_attempt_at 等,由
     * ck_object_deletion_completion 强制);updateById 默认策略跳过 null 字段,
     * 曾导致完成迁移永远违反约束、任务卡死。此处锁定四个可空迁移列必须
     * 声明 ALWAYS 更新策略。
     */
    @Test
    void nullableTransitionColumnsUpdateWithAlwaysStrategy() throws NoSuchFieldException {
        for (String field : new String[] {"lastError", "nextAttemptAt", "completedAt", "failedAt"}) {
            com.baomidou.mybatisplus.annotation.TableField annotation = ObjectDeletionJob.class
                    .getDeclaredField(field)
                    .getAnnotation(com.baomidou.mybatisplus.annotation.TableField.class);
            assertThat(annotation)
                    .as("字段 %s 缺少 @TableField(updateStrategy = ALWAYS)", field)
                    .isNotNull();
            assertThat(annotation.updateStrategy())
                    .as("字段 %s 的更新策略", field)
                    .isEqualTo(com.baomidou.mybatisplus.annotation.FieldStrategy.ALWAYS);
        }
    }
}
