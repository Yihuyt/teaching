package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.shared.storage.ObjectStorageStatusProvider;
import cn.utcy.teaching.resource.domain.ObjectDeletionJob;
import cn.utcy.teaching.resource.domain.ObjectDeletionJobStatus;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
class OssStatusProvider implements ObjectStorageStatusProvider {

    private final OSS oss;
    private final OssProperties properties;
    private final ObjectDeletionJobMapper deletionJobs;

    OssStatusProvider(
            OSS oss,
            OssProperties properties,
            ObjectDeletionJobMapper deletionJobs
    ) {
        this.oss = oss;
        this.properties = properties;
        this.deletionJobs = deletionJobs;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional
    public long retryFailedDeletions() {
        return deletionJobs.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<cn.utcy.teaching.resource.domain.ObjectDeletionJob>()
                .eq("status", "failed")
                .set("status", "pending")
                .set("attempts", 0)
                .set("next_attempt_at", Instant.now())
                .set("failed_at", null));
    }

    @Override
    public ObjectStorageStatus check() {
        Instant checkedAt = Instant.now();
        try {
            boolean connected = oss.doesBucketExist(properties.bucket());
            return new ObjectStorageStatus(
                    properties.endpoint(),
                    properties.bucket(),
                    connected,
                    checkedAt,
                    connected ? null : "bucket_not_found",
                    count(ObjectDeletionJobStatus.PENDING),
                    count(ObjectDeletionJobStatus.FAILED));
        } catch (OSSException exception) {
            return new ObjectStorageStatus(
                    properties.endpoint(),
                    properties.bucket(),
                    false,
                    checkedAt,
                    exception.getErrorCode(),
                    count(ObjectDeletionJobStatus.PENDING),
                    count(ObjectDeletionJobStatus.FAILED));
        } catch (ClientException exception) {
            return new ObjectStorageStatus(
                    properties.endpoint(),
                    properties.bucket(),
                    false,
                    checkedAt,
                    "client_error",
                    count(ObjectDeletionJobStatus.PENDING),
                    count(ObjectDeletionJobStatus.FAILED));
        }
    }

    private long count(ObjectDeletionJobStatus status) {
        return deletionJobs.selectCount(new LambdaQueryWrapper<ObjectDeletionJob>()
                .eq(ObjectDeletionJob::getStatus, status));
    }
}
