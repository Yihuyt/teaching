package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.judgecontract.TestcasePackageException;
import cn.utcy.teaching.judgecontract.TestcasePackage;
import cn.utcy.teaching.judgecontract.TestcasePackageReader;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class TestcaseObjectValidator {
    private final OSS oss;
    private final TestcasePackageReader packageReader;

    public TestcaseObjectValidator(OSS oss, TestcasePackageReader packageReader) {
        this.oss = oss;
        this.packageReader = packageReader;
    }

    public void validate(
            String bucket,
            String objectKey,
            long expectedSizeBytes,
            String expectedSha256,
            long problemId
    ) {
        read(bucket, objectKey, expectedSizeBytes, expectedSha256, problemId);
    }

    public TestcasePackage read(
            String bucket,
            String objectKey,
            long expectedSizeBytes,
            String expectedSha256,
            long problemId
    ) {
        if (!oss.doesObjectExist(bucket, objectKey)) {
            throw new ConflictException("尚未找到已上传的测试数据文件");
        }
        Path archive = createTemporaryArchive();
        RuntimeException failure = null;
        try {
            downloadAndVerify(
                    bucket,
                    objectKey,
                    expectedSizeBytes,
                    expectedSha256,
                    archive);
            return packageReader.read(archive, problemId);
        } catch (TestcasePackageException exception) {
            failure = new ConflictException(
                    "测试数据文件格式不正确：" + exception.getMessage());
            throw failure;
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            deleteTemporaryArchive(archive, failure);
        }
    }

    private Path createTemporaryArchive() {
        try {
            return Files.createTempFile("teaching-testcase-validation-", ".zip");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建测试包校验临时文件", exception);
        }
    }

    private void downloadAndVerify(
            String bucket,
            String objectKey,
            long expectedSizeBytes,
            String expectedSha256,
            Path archive
    ) {
        try (OSSObject object = oss.getObject(bucket, objectKey);
             InputStream input = object.getObjectContent();
             OutputStream output = Files.newOutputStream(archive)) {
            if (object.getObjectMetadata().getContentLength() != expectedSizeBytes) {
                throw new ConflictException("测试数据文件大小与选择的文件不一致");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long actualSize = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                actualSize = Math.addExact(actualSize, read);
                if (actualSize > expectedSizeBytes) {
                    throw new ConflictException("测试数据文件大小与选择的文件不一致");
                }
                digest.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            if (actualSize != expectedSizeBytes) {
                throw new ConflictException("测试数据文件大小与选择的文件不一致");
            }
            String actualSha256 = HexFormat.of().formatHex(digest.digest());
            if (!actualSha256.equals(expectedSha256)) {
                throw new ConflictException("测试数据文件内容校验失败，请重新上传");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取 OSS 测试包进行契约校验失败", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        } catch (ArithmeticException exception) {
            throw new ConflictException("测试数据文件超过大小限制");
        }
    }

    private void deleteTemporaryArchive(
            Path archive,
            RuntimeException primaryFailure
    ) {
        try {
            Files.deleteIfExists(archive);
        } catch (IOException exception) {
            IllegalStateException cleanupFailure = new IllegalStateException(
                    "无法清理测试包校验临时文件", exception);
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
                return;
            }
            throw cleanupFailure;
        }
    }
}
