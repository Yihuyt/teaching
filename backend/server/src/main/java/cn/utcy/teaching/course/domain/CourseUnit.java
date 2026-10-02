package cn.utcy.teaching.course.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("course_unit")
public class CourseUnit {

    @TableId
    private Long id;
    private Long courseId;
    // 移到根级时 parent_id 必须真正写回 NULL(默认策略跳过 null,会撞 parent_scope 的 CHECK)
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long parentId;
    private long parentScope;
    private String title;
    private int position;
    private Instant createdAt;
    private Instant updatedAt;

    protected CourseUnit() {
    }

    public CourseUnit(Long id, Long courseId, Long parentId, String title, int position,
                         Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.courseId = courseId;
        this.parentId = parentId;
        this.parentScope = parentScope(parentId);
        this.title = title;
        this.position = position;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static CourseUnit create(
            long courseId,
            Long parentId,
            String title,
            int position
    ) {
        Instant now = Instant.now();
        return new CourseUnit(null, courseId, parentId, title, position, now, now);
    }

    public void update(String title) {
        this.title = title;
        this.updatedAt = Instant.now();
    }

    public void moveTo(Long parentId, int position) {
        this.parentId = parentId;
        this.parentScope = parentScope(parentId);
        this.position = position;
        this.updatedAt = Instant.now();
    }

    private static long parentScope(Long parentId) {
        return parentId == null ? 0L : parentId;
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

    public long getParentScope() {
        return parentScope;
    }

    public String getTitle() {
        return title;
    }

    public int getPosition() {
        return position;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
