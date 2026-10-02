package cn.utcy.teaching.resource.infrastructure;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.storage.ObjectStorageIntegrityVerifier;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
class OssObjectIntegrityVerifier implements ObjectStorageIntegrityVerifier {

    private final OSS oss;

    OssObjectIntegrityVerifier(OSS oss) {
        this.oss = oss;
    }

    @Override
    public void verify(
            String bucket,
            String objectKey,
            long expectedSizeBytes,
            String expectedSha256
    ) {
        if (!oss.doesObjectExist(bucket, objectKey)) {
            throw new ConflictException("尚未找到已上传的文件");
        }
        try (OSSObject object = oss.getObject(bucket, objectKey);
             InputStream input = object.getObjectContent()) {
            if (object.getObjectMetadata().getContentLength() != expectedSizeBytes) {
                throw new ConflictException("上传文件大小与选择的文件不一致");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long actualSize = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                actualSize += read;
                if (actualSize > expectedSizeBytes) {
                    throw new ConflictException("上传文件大小与选择的文件不一致");
                }
                digest.update(buffer, 0, read);
            }
            if (actualSize != expectedSizeBytes) {
                throw new ConflictException("上传文件大小与选择的文件不一致");
            }
            String actualSha256 = HexFormat.of().formatHex(digest.digest());
            if (!actualSha256.equals(expectedSha256)) {
                throw new ConflictException("上传文件内容校验失败，请重新上传");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取 OSS 对象进行完整性校验失败", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }
}
