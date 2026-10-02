package cn.utcy.teaching.notification.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("announcement")
public class Announcement {

    @TableId
    private Long id;
    private Long courseId;
    private Long ownerId;
    private String title;
    private String contentMarkdown;
    private Instant createdAt;
    private Instant updatedAt;

    protected Announcement() {
    }

    public Announcement(Long id, Long courseId, Long ownerId, String title, String contentMarkdown,
                        Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.courseId = courseId;
        this.ownerId = ownerId;
        this.title = title;
        this.contentMarkdown = contentMarkdown;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Announcement create(Long courseId, long ownerId, String title, String contentMarkdown) {
        Instant now = Instant.now();
        return new Announcement(null, courseId, ownerId, title, contentMarkdown, now, now);
    }

    public void update(String title, String contentMarkdown) {
        this.title = title;
        this.contentMarkdown = contentMarkdown;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public String getTitle() {
        return title;
    }

    public String getContentMarkdown() {
        return contentMarkdown;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
