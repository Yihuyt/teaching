package cn.utcy.teaching.shared.storage;

public interface ObjectStorageDeletionQueue {

    void enqueue(String bucket, String objectKey);
}
