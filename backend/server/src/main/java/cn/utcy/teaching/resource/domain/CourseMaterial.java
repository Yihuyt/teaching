package cn.utcy.teaching.resource.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("course_material")
public class CourseMaterial {

    @TableId
    private Long id;
    private Long courseId;
    private Long parentId;
    /** = COALESCE(parent_id, 0):让 (course_id, parent_scope, name) 唯一键对根目录也生效 */
    private Long parentScope;
    private String name;
    private MaterialKind kind;
    private String objectKey;
    private String contentType;
    private Long sizeBytes;
    private String sha256;
    private MaterialState state;
    private Instant createdAt;
    private Instant updatedAt;

    protected CourseMaterial() {
    }

    public CourseMaterial(
            Long id,
            Long courseId,
            Long parentId,
            String name,
            MaterialKind kind,
            String objectKey,
            String contentType,
            Long sizeBytes,
            String sha256,
            MaterialState state,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.courseId = courseId;
        this.parentId = parentId;
        this.parentScope = parentId == null ? 0L : parentId;
        this.name = name;
        this.kind = kind;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.state = state;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static CourseMaterial folder(long courseId, Long parentId, String name) {
        Instant now = Instant.now();
        return new CourseMaterial(
                null,
                courseId,
                parentId,
                name,
                MaterialKind.FOLDER,
                null,
                null,
                null,
                null,
                MaterialState.ACTIVE,
                now,
                now);
    }

    public static CourseMaterial pendingFile(
            long courseId,
            Long parentId,
            String name,
            String objectKey,
            String contentType,
            long sizeBytes,
            String sha256
    ) {
        Instant now = Instant.now();
        return new CourseMaterial(
                null,
                courseId,
                parentId,
                name,
                MaterialKind.FILE,
                objectKey,
                contentType,
                sizeBytes,
                sha256,
                MaterialState.PENDING_UPLOAD,
                now,
                now);
    }

    public void activate() {
        state = MaterialState.ACTIVE;
        updatedAt = Instant.now();
    }

    public void rename(String name) {
        this.name = name;
        updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Long getParentId() {
        return parentId;
    }

    public String getName() {
        return name;
    }

    public MaterialKind getKind() {
        return kind;
    }

    public String getObjectKey() {
        return objectKey;
    }

    public String getContentType() {
        return contentType;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public MaterialState getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
