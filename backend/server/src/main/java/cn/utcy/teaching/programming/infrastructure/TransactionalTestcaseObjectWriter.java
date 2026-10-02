package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.shared.error.ConflictException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Component
public class TransactionalTestcaseObjectWriter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(TransactionalTestcaseObjectWriter.class);

    private final OSS oss;
    private final TestcaseOssProperties properties;

    public TransactionalTestcaseObjectWriter(
            OSS oss,
            TestcaseOssProperties properties
    ) {
        this.oss = oss;
        this.properties = properties;
    }

    public void uploadNew(
            Path file,
            String objectKey,
            String sha256,
            long sizeBytes,
            String contentType
    ) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("OSS 对象写入必须在数据库事务中执行");
        }
        requireFileSize(file, sizeBytes);
        if (oss.doesObjectExist(properties.bucket(), objectKey)) {
            throw new ConflictException("相同内容的测试数据文件已经存在，不能覆盖");
        }
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(sizeBytes);
        metadata.setContentType(contentType);
        metadata.setUserMetadata(Map.of("sha256", sha256));
        metadata.setHeader("x-oss-forbid-overwrite", "true");
        PutObjectRequest request = new PutObjectRequest(
                properties.bucket(), objectKey, file.toFile(), metadata);
        oss.putObject(request);
        try {
            registerRollbackCleanup(objectKey);
        } catch (RuntimeException exception) {
            try {
                oss.deleteObject(properties.bucket(), objectKey);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private void registerRollbackCleanup(String objectKey) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status == STATUS_COMMITTED) {
                            return;
                        }
                        try {
                            oss.deleteObject(properties.bucket(), objectKey);
                        } catch (RuntimeException exception) {
                            LOGGER.error(
                                    "数据库事务回滚后清理 OSS 对象失败，bucket={}，objectKey={}",
                                    properties.bucket(),
                                    objectKey,
                                    exception);
                        }
                    }
                });
    }

    private void requireFileSize(Path file, long expectedSizeBytes) {
        try {
            if (Files.size(file) != expectedSizeBytes) {
                throw new IllegalStateException("待上传文件大小与声明不一致");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取待上传文件大小", exception);
        }
    }
}
