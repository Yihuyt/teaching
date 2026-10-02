package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.shared.storage.ObjectStorageDeletionQueue;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.resource.domain.ObjectDeletionJob;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class ObjectDeletionQueueService implements ObjectStorageDeletionQueue {

    private final ObjectDeletionJobMapper jobs;

    ObjectDeletionQueueService(ObjectDeletionJobMapper jobs) {
        this.jobs = jobs;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String bucket, String objectKey) {
        if (jobs.insert(ObjectDeletionJob.pending(bucket, objectKey)) != 1) {
            throw new ConflictException("文件删除任务创建失败");
        }
    }
}
