package cn.utcy.teaching.analytics.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("learning_event")
public class LearningEventEntity {

    @TableId
    private Long id;
    private Long courseId;
    private Long accountId;
    private String eventType;
    private String objectType;
    private Long objectId;
    private String detail;
    private LocalDateTime occurredAt;

    protected LearningEventEntity() {
    }

    public LearningEventEntity(long courseId, long accountId, String eventType, String objectType,
                               long objectId, String detail, LocalDateTime occurredAt) {
        this.courseId = courseId;
        this.accountId = accountId;
        this.eventType = eventType;
        this.objectType = objectType;
        this.objectId = objectId;
        this.detail = detail;
        this.occurredAt = occurredAt;
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getObjectType() {
        return objectType;
    }

    public Long getObjectId() {
        return objectId;
    }

    public String getDetail() {
        return detail;
    }

    public LocalDateTime getOccurredAt() {
        return occurredAt;
    }
}
