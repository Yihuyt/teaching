package cn.utcy.teaching.blockcoding.infrastructure;

import cn.utcy.teaching.shared.storage.ObjectStorageDeletionQueue;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

@Component
public class BlockCodingStorage {
    private final OSS oss;
    private final BlockCodingOssProperties bucket;
    private final ObjectStorageDeletionQueue deletionQueue;

    public BlockCodingStorage(OSS oss, BlockCodingOssProperties bucket, ObjectStorageDeletionQueue deletionQueue) {
        this.oss = oss;
        this.bucket = bucket;
        this.deletionQueue = deletionQueue;
    }

    public String objectKey(long accountId, long projectId) {
        return "blockcoding/" + accountId + "/" + projectId + ".sb3";
    }

    public void put(String objectKey, byte[] content) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        metadata.setContentType("application/x.scratch.sb3");
        oss.putObject(bucket.bucket(), objectKey, new ByteArrayInputStream(content), metadata);
    }

    public byte[] get(String objectKey) {
        OSSObject object = oss.getObject(bucket.bucket(), objectKey);
        try (InputStream stream = object.getObjectContent()) {
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException("读取工程文件失败: " + objectKey, exception);
        }
    }

    public void enqueueDeletion(String objectKey) {
        deletionQueue.enqueue(bucket.bucket(), objectKey);
    }
}
