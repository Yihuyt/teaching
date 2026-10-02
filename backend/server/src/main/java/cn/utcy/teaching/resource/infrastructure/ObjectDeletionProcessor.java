package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.resource.domain.ObjectDeletionJob;
import cn.utcy.teaching.resource.domain.ObjectDeletionJobStatus;
import com.aliyun.oss.OSS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ObjectDeletionProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(ObjectDeletionProcessor.class);
    private static final int MAXIMUM_ATTEMPTS = 5;
    private final ObjectDeletionJobMapper jobs;
    private final OSS oss;

    ObjectDeletionProcessor(ObjectDeletionJobMapper jobs, OSS oss) {
        this.jobs = jobs;
        this.oss = oss;
    }

    @Transactional
    public void process(String jobId) {
        ObjectDeletionJob job = jobs.selectForUpdate(jobId);
        if (job == null || job.getStatus() != ObjectDeletionJobStatus.PENDING) {
            return;
        }
        try {
            oss.deleteObject(job.getBucketName(), job.getObjectKey());
            job.complete();
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            job.recordFailure(
                    Text.truncate(message, 1000),
                    MAXIMUM_ATTEMPTS,
                    java.time.Instant.now());
            LOG.warn(
                    "OSS 对象删除失败，jobId={}，attempt={}，status={}，error={}",
                    job.getId(),
                    job.getAttempts(),
                    job.getStatus().value(),
                    job.getLastError());
        }
        if (jobs.updateById(job) != 1) {
            throw new ConflictException("对象删除任务状态已变化，更新未生效");
        }
    }
}
