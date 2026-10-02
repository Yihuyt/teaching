package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.resource.domain.ObjectDeletionJob;
import cn.utcy.teaching.resource.domain.ObjectDeletionJobStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;

/** 派发只做捞取:OSS 网络调用在专用单线程里执行,不占共享调度线程 */
@Component
class ObjectDeletionDispatcher {

    private final ObjectDeletionJobMapper jobs;
    private final ObjectDeletionProcessor processor;
    private final ExecutorService deletions = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("object-deletion").factory());

    ObjectDeletionDispatcher(ObjectDeletionJobMapper jobs, ObjectDeletionProcessor processor) {
        this.jobs = jobs;
        this.processor = processor;
    }

    @PreDestroy
    void shutdown() {
        deletions.shutdownNow();
    }

    @Scheduled(fixedDelayString = "${teaching.object-storage.deletion-delay}")
    public void dispatch() {
        List<String> pendingIds = jobs.selectList(
                        new LambdaQueryWrapper<ObjectDeletionJob>()
                                .eq(ObjectDeletionJob::getStatus, ObjectDeletionJobStatus.PENDING)
                                .le(ObjectDeletionJob::getNextAttemptAt, Instant.now())
                                .orderByAsc(ObjectDeletionJob::getNextAttemptAt)
                                .last("LIMIT 20"))
                .stream()
                .map(ObjectDeletionJob::getId)
                .toList();
        if (pendingIds.isEmpty()) {
            return;
        }
        deletions.execute(() -> pendingIds.forEach(processor::process));
    }
}
