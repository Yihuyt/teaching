package cn.utcy.teaching.shared.storage;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

public interface ObjectStorageStatusProvider {

    ObjectStorageStatus check();

    /** 把耗尽重试的删除任务重新排队(凭据 / 网络修复后的人工恢复入口),返回重投条数 */
    long retryFailedDeletions();

    record ObjectStorageStatus(
            String endpoint,
            String bucket,
            boolean connected,
            Instant checkedAt,
            @Schema(nullable = true) String errorCode,
            long pendingDeletionCount,
            long failedDeletionCount
    ) {
    }
}
