package cn.utcy.teaching.course.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("course")
public class Course {

    @TableId
    private Long id;
    private Long ownerId;
    private String joinCode;
    private String title;
    private String descriptionMarkdown;
    private boolean published;
    private Instant createdAt;
    private Instant updatedAt;

    protected Course() {
    }

    public Course(Long id, Long ownerId, String joinCode, String title, String descriptionMarkdown,
                  boolean published, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.ownerId = ownerId;
        this.joinCode = joinCode;
        this.title = title;
        this.descriptionMarkdown = descriptionMarkdown;
        this.published = published;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Course create(
            long ownerId,
            String joinCode,
            String title,
            String descriptionMarkdown
    ) {
        Instant now = Instant.now();
        return new Course(
                null,
                ownerId,
                joinCode,
                title,
                descriptionMarkdown,
                false,
                now,
                now);
    }

    public void update(String title, String descriptionMarkdown) {
        this.title = title;
        this.descriptionMarkdown = descriptionMarkdown;
        this.updatedAt = Instant.now();
    }

    /** 已发布:学生可凭课程码加入、成员可进入学习;可随时取消 */
    public void publish() {
        if (published) {
            throw new IllegalStateException("课程已发布");
        }
        published = true;
        updatedAt = Instant.now();
    }

    public void unpublish() {
        if (!published) {
            throw new IllegalStateException("课程未发布");
        }
        published = false;
        updatedAt = Instant.now();
    }

    public void rotateJoinCode(String joinCode) {
        this.joinCode = joinCode;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getJoinCode() {
        return joinCode;
    }

    public String getTitle() {
        return title;
    }

    public String getDescriptionMarkdown() {
        return descriptionMarkdown;
    }

    public boolean isPublished() {
        return published;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
