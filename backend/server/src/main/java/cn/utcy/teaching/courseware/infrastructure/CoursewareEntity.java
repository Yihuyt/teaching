package cn.utcy.teaching.courseware.infrastructure;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 课件行:归属课程的元数据 + 整份 Stage JSON(body 为 MySQL JSON 列)。
 * title/scene_count 是与 body 同事务写入的查询投影(列表页免解析 body);
 * version 是内容变更计数(每次写入 +1,前端据此判断是否需要重取);
 * published 是唯一的可见性开关:未发布的课件学生不可见。写入一律先锁行(selectForUpdate)再改,不靠乐观锁。
 */
@TableName("courseware")
public class CoursewareEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long courseId;
    private String title;
    private Integer sceneCount;
    private String body;
    private Long version;
    private boolean published;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    protected CoursewareEntity() {
    }

    public CoursewareEntity(long courseId, String title, int sceneCount, String body, LocalDateTime now) {
        this.courseId = courseId;
        this.title = title;
        this.sceneCount = sceneCount;
        this.body = body;
        this.createdAt = now;
        this.updatedAt = now;
        this.version = 0L;
        this.published = false;
    }

    /** 内容写入(须已持有行锁):投影列同步,version +1 */
    public void replaceContent(String title, int sceneCount, String body, LocalDateTime now) {
        this.title = title;
        this.sceneCount = sceneCount;
        this.body = body;
        this.version = (version == null ? 0L : version) + 1;
        this.updatedAt = now;
    }

    public void publish(LocalDateTime now) {
        this.published = true;
        this.updatedAt = now;
    }

    public void unpublish(LocalDateTime now) {
        this.published = false;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public String getTitle() {
        return title;
    }

    public Integer getSceneCount() {
        return sceneCount;
    }

    public String getBody() {
        return body;
    }

    public Long getVersion() {
        return version;
    }

    public boolean isPublished() {
        return published;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
