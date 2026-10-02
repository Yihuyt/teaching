package cn.utcy.teaching.shared.storage;

public interface ObjectStorageIntegrityVerifier {

    void verify(String bucket, String objectKey, long expectedSizeBytes, String expectedSha256);
}
