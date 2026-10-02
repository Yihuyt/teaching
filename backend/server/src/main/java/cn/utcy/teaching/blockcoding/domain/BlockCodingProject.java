package cn.utcy.teaching.blockcoding.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("blockcoding_project")
public class BlockCodingProject {

    @TableId
    private Long id;
    private Long courseId;
    private Long ownerAccountId;
    private String name;
    private String ossObjectKey;
    private Long fileSize;
    private Instant fileUpdatedAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected BlockCodingProject() {
    }

    public static BlockCodingProject create(long courseId, long ownerAccountId, String name) {
        BlockCodingProject project = new BlockCodingProject();
        Instant now = Instant.now();
        project.courseId = courseId;
        project.ownerAccountId = ownerAccountId;
        project.name = name;
        project.createdAt = now;
        project.updatedAt = now;
        return project;
    }

    public void rename(String newName) {
        this.name = newName;
        this.updatedAt = Instant.now();
    }

    public void recordFile(String objectKey, long size) {
        Instant now = Instant.now();
        this.ossObjectKey = objectKey;
        this.fileSize = size;
        this.fileUpdatedAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Long getOwnerAccountId() {
        return ownerAccountId;
    }

    public String getName() {
        return name;
    }

    public String getOssObjectKey() {
        return ossObjectKey;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public Instant getFileUpdatedAt() {
        return fileUpdatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
