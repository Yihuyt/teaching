package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.shared.error.ConflictException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OssObjectIntegrityVerifierTest {

    @Test
    void verifiesTheBytesReadFromOssInsteadOfTrustingMetadataHash() {
        byte[] content = "真实对象内容".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OSS oss = objectStorage(content);
        OssObjectIntegrityVerifier verifier = new OssObjectIntegrityVerifier(oss);

        assertThatCode(() -> verifier.verify(
                "bucket",
                "object",
                content.length,
                "c52a0c7258aa16d4cc8c668eca10e4e03c735df05047a1bce874410990d43565"))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnObjectWhoseActualBytesDoNotMatchDeclaredSha256() {
        byte[] content = "真实对象内容".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        OssObjectIntegrityVerifier verifier = new OssObjectIntegrityVerifier(objectStorage(content));

        assertThatThrownBy(() -> verifier.verify(
                "bucket",
                "object",
                content.length,
                "0".repeat(64)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("上传文件内容校验失败，请重新上传");
    }

    private OSS objectStorage(byte[] content) {
        OSS oss = mock(OSS.class);
        OSSObject object = new OSSObject();
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        object.setObjectMetadata(metadata);
        object.setObjectContent(new ByteArrayInputStream(content));
        when(oss.doesObjectExist("bucket", "object")).thenReturn(true);
        when(oss.getObject("bucket", "object")).thenReturn(object);
        return oss;
    }
}
