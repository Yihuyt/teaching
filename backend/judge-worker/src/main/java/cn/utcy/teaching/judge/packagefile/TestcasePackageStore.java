package cn.utcy.teaching.judge.packagefile;

import cn.utcy.teaching.judge.config.JudgeProperties;
import cn.utcy.teaching.judge.config.OssProperties;
import cn.utcy.teaching.judgecontract.Sha256;
import cn.utcy.teaching.judgecontract.TestcasePackage;
import cn.utcy.teaching.judgecontract.TestcasePackageException;
import cn.utcy.teaching.judgecontract.TestcasePackageReader;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GetObjectRequest;
import com.aliyun.oss.model.ObjectMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class TestcasePackageStore {

    private static final Logger log = LoggerFactory.getLogger(TestcasePackageStore.class);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final OSS oss;
    private final OssProperties ossProperties;
    private final JudgeProperties judgeProperties;
    private final TestcasePackageReader reader;

    public TestcasePackageStore(
            OSS oss,
            OssProperties ossProperties,
            JudgeProperties judgeProperties,
            TestcasePackageReader reader
    ) {
        this.oss = oss;
        this.ossProperties = ossProperties;
        this.judgeProperties = judgeProperties;
        this.reader = reader;
    }

    public TestcasePackage load(long problemId, String expectedSha256) {
        if (problemId < 1 || expectedSha256 == null || !SHA256.matcher(expectedSha256).matches()) {
            throw new TestcasePackageException("测试包定位参数非法");
        }
        Path cacheRoot = judgeProperties.testcaseCache().toAbsolutePath().normalize();
        Path archive = cacheRoot.resolve(Long.toString(problemId)).resolve(expectedSha256 + ".zip").normalize();
        if (!archive.startsWith(cacheRoot)) {
            throw new TestcasePackageException("测试包缓存路径越界");
        }
        try {
            Files.createDirectories(archive.getParent());
            if (Files.exists(archive, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
                    throw new TestcasePackageException("测试包缓存目标不是普通文件");
                }
                try {
                    verifyArchiveHash(archive, expectedSha256);
                } catch (TestcasePackageException exception) {
                    Files.delete(archive);
                    log.warn(
                            "已删除散列不一致的测试包缓存，problemId={}，sha256={}",
                            problemId,
                            expectedSha256
                    );
                    download(problemId, expectedSha256, archive);
                }
            } else {
                download(problemId, expectedSha256, archive);
            }
            verifyArchiveHash(archive, expectedSha256);
            // 触摸 mtime:清理器按最近使用时间回收,正在被评测使用的包不会老化
            Files.setLastModifiedTime(archive, java.nio.file.attribute.FileTime.from(java.time.Instant.now()));
            return reader.read(archive, problemId);
        } catch (TestcasePackageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new TestcasePackageException("无法访问测试包缓存", exception);
        }
    }

    private void download(long problemId, String expectedSha256, Path archive) throws IOException {
        Path temporary = archive.resolveSibling(archive.getFileName() + "." + UUID.randomUUID() + ".part");
        String objectKey = "judge-testcases/" + problemId + "/" + expectedSha256 + ".zip";
        try {
            ObjectMetadata metadata = oss.getObjectMetadata(ossProperties.testcaseBucket(), objectKey);
            validateArchiveSize(metadata.getContentLength());
            oss.getObject(new GetObjectRequest(ossProperties.testcaseBucket(), objectKey), temporary.toFile());
            verifyArchiveHash(temporary, expectedSha256);
            try {
                Files.move(temporary, archive, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException ignored) {
                Files.deleteIfExists(temporary);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new TestcasePackageException("测试包缓存文件系统必须支持原子移动", exception);
            }
        } catch (RuntimeException | IOException exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }
    }

    private void verifyArchiveHash(Path archive, String expectedSha256) throws IOException {
        long size = Files.size(archive);
        validateArchiveSize(size);
        if (!Sha256.file(archive).equals(expectedSha256)) {
            throw new TestcasePackageException("测试包 SHA-256 与任务不一致");
        }
    }

    private void validateArchiveSize(long size) {
        if (size < 1 || size > TestcasePackageReader.MAX_ARCHIVE_BYTES) {
            throw new TestcasePackageException("测试包压缩文件大小非法");
        }
    }
}
