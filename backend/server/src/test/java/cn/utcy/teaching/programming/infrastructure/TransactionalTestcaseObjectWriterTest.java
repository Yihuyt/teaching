package cn.utcy.teaching.programming.infrastructure;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.PutObjectRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TransactionalTestcaseObjectWriterTest {

    @TempDir
    Path temporaryDirectory;

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void uploadRequiresActiveDatabaseTransaction() throws IOException {
        OSS oss = mock(OSS.class);
        TransactionalTestcaseObjectWriter writer = writer(oss);
        Path archive = createArchive();

        assertThatThrownBy(() -> writer.uploadNew(
                archive,
                "judge-testcases/1/a.zip",
                "a".repeat(64),
                Files.size(archive),
                "application/zip"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OSS 对象写入必须在数据库事务中执行");

        verify(oss, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void rollbackDeletesExactlyTheNewlyUploadedObject() throws IOException {
        OSS oss = mock(OSS.class);
        when(oss.doesObjectExist("testcases", "judge-testcases/1/a.zip"))
                .thenReturn(false);
        TransactionalTestcaseObjectWriter writer = writer(oss);
        Path archive = createArchive();
        TransactionSynchronizationManager.initSynchronization();

        writer.uploadNew(
                archive,
                "judge-testcases/1/a.zip",
                "a".repeat(64),
                Files.size(archive),
                "application/zip");
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_ROLLED_BACK);
        }

        var order = inOrder(oss);
        order.verify(oss).doesObjectExist(
                "testcases", "judge-testcases/1/a.zip");
        order.verify(oss).putObject(any(PutObjectRequest.class));
        order.verify(oss).deleteObject(
                "testcases", "judge-testcases/1/a.zip");
    }

    @Test
    void commitKeepsUploadedObject() throws IOException {
        OSS oss = mock(OSS.class);
        when(oss.doesObjectExist("testcases", "problem-packages/1/a.zip"))
                .thenReturn(false);
        TransactionalTestcaseObjectWriter writer = writer(oss);
        Path archive = createArchive();
        TransactionSynchronizationManager.initSynchronization();

        writer.uploadNew(
                archive,
                "problem-packages/1/a.zip",
                "a".repeat(64),
                Files.size(archive),
                "application/zip");
        for (TransactionSynchronization synchronization
                : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(
                    TransactionSynchronization.STATUS_COMMITTED);
        }

        verify(oss).putObject(any(PutObjectRequest.class));
        verify(oss, never()).deleteObject(
                "testcases", "problem-packages/1/a.zip");
    }

    private TransactionalTestcaseObjectWriter writer(OSS oss) {
        return new TransactionalTestcaseObjectWriter(
                oss,
                new TestcaseOssProperties("testcases"));
    }

    private Path createArchive() throws IOException {
        Path archive = temporaryDirectory.resolve("package.zip");
        Files.writeString(archive, "test-package");
        return archive;
    }
}
