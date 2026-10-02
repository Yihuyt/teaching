package cn.utcy.teaching.judge.packagefile;

import cn.utcy.teaching.judge.config.OssProperties;
import com.aliyun.oss.OSS;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssStartupVerifierTest {

    private static final String BUCKET = "testcase-bucket";
    private static final OssProperties PROPERTIES = new OssProperties(
            "https://oss-cn-hangzhou.aliyuncs.com",
            BUCKET,
            Path.of("/run/secrets/aliyun-oss-credentials")
    );

    @Test
    void acceptsAnAccessibleBucket() {
        OSS oss = mock(OSS.class);
        when(oss.doesBucketExist(BUCKET)).thenReturn(true);

        new OssStartupVerifier(oss, PROPERTIES).run(null);

        verify(oss).doesBucketExist(BUCKET);
    }

    @Test
    void rejectsAMissingOrForbiddenBucket() {
        OSS oss = mock(OSS.class);
        when(oss.doesBucketExist(BUCKET)).thenReturn(false);

        assertThatThrownBy(() -> new OssStartupVerifier(oss, PROPERTIES).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在或当前凭据无权访问");
    }

    @Test
    void propagatesAnOssProbeFailure() {
        OSS oss = mock(OSS.class);
        RuntimeException failure = new RuntimeException("OSS probe failed");
        when(oss.doesBucketExist(BUCKET)).thenThrow(failure);

        assertThatThrownBy(() -> new OssStartupVerifier(oss, PROPERTIES).run(null))
                .isSameAs(failure);
    }
}
