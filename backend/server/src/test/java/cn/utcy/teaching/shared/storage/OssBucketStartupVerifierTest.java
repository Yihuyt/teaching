package cn.utcy.teaching.shared.storage;

import cn.utcy.teaching.programming.infrastructure.TestcaseOssProperties;
import cn.utcy.teaching.resource.infrastructure.OssProperties;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssBucketStartupVerifierTest {

    private final OSS oss = mock(OSS.class);

    @Test
    void verifiesSharedBucketOnce() {
        when(oss.doesBucketExist("teaching")).thenReturn(true);
        OssBucketStartupVerifier verifier = verifier("teaching", "teaching");

        verifier.run(null);

        verify(oss, times(1)).doesBucketExist("teaching");
    }

    @Test
    void verifiesResourceAndTestcaseBucketsSeparately() {
        when(oss.doesBucketExist("resources")).thenReturn(true);
        when(oss.doesBucketExist("testcases")).thenReturn(true);
        OssBucketStartupVerifier verifier = verifier("resources", "testcases");

        verifier.run(null);

        verify(oss).doesBucketExist("resources");
        verify(oss).doesBucketExist("testcases");
    }

    @Test
    void missingBucketFailsStartup() {
        when(oss.doesBucketExist("resources")).thenReturn(false);
        OssBucketStartupVerifier verifier = verifier("resources", "testcases");

        assertThatThrownBy(() -> verifier.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OSS bucket 不存在或当前凭据无权访问：resources");
    }

    @Test
    void clientFailureIsNotSwallowed() {
        ClientException failure = new ClientException("connection refused");
        when(oss.doesBucketExist("resources")).thenThrow(failure);
        OssBucketStartupVerifier verifier = verifier("resources", "testcases");

        assertThatThrownBy(() -> verifier.run(null))
                .isSameAs(failure);
    }

    private OssBucketStartupVerifier verifier(
            String resourceBucket,
            String testcaseBucket
    ) {
        OssProperties resourceOss = new OssProperties(
                "https://oss-cn-hangzhou.aliyuncs.com",
                resourceBucket,
                Path.of("/run/secrets/oss"));
        return new OssBucketStartupVerifier(oss, List.of(
                resourceOss,
                new TestcaseOssProperties(testcaseBucket)));
    }
}
