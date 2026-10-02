package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.resource.domain.CourseMaterial;
import cn.utcy.teaching.resource.domain.MaterialState;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 上传残留回收:拿到预签名地址后没有确认的资料行永远停在 pending_upload,
 * 对象可能已经打到了 OSS 却无人知晓。超过保留期的行删除,其对象排入删除队列(对象不存在也幂等)。
 */
@Component
class PendingUploadJanitor {

    private static final Logger log = LoggerFactory.getLogger(PendingUploadJanitor.class);
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final int BATCH_LIMIT = 200;

    private final CourseMaterialMapper materials;
    private final ObjectDeletionQueueService deletionQueue;
    private final OssProperties oss;
    private final TransactionTemplate transactions;

    PendingUploadJanitor(
            CourseMaterialMapper materials,
            ObjectDeletionQueueService deletionQueue,
            OssProperties oss,
            TransactionTemplate transactions
    ) {
        this.materials = materials;
        this.deletionQueue = deletionQueue;
        this.oss = oss;
        this.transactions = transactions;
    }

    @Scheduled(initialDelay = 60_000, fixedDelay = 3_600_000)
    public void cleanup() {
        Instant threshold = Instant.now().minus(RETENTION);
        List<CourseMaterial> expired = materials.selectList(new LambdaQueryWrapper<CourseMaterial>()
                .eq(CourseMaterial::getState, MaterialState.PENDING_UPLOAD)
                .lt(CourseMaterial::getCreatedAt, threshold)
                .last("LIMIT " + BATCH_LIMIT));
        for (CourseMaterial material : expired) {
            try {
                transactions.executeWithoutResult(status -> {
                    // 条件删除:扫描后教师可能已确认上传,决不能把已激活的资料删掉
                    int deleted = materials.delete(new LambdaQueryWrapper<CourseMaterial>()
                            .eq(CourseMaterial::getId, material.getId())
                            .eq(CourseMaterial::getState, MaterialState.PENDING_UPLOAD));
                    if (deleted == 1 && material.getObjectKey() != null) {
                        deletionQueue.enqueue(oss.bucket(), material.getObjectKey());
                    }
                });
            } catch (RuntimeException exception) {
                log.error("回收未完成上传的资料失败，跳过并继续：materialId={}", material.getId(), exception);
            }
        }
        if (!expired.isEmpty()) {
            log.info("回收 {} 条超过 24 小时未确认上传的资料", expired.size());
        }
    }
}
