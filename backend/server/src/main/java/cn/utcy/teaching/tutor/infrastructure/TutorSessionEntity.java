package cn.utcy.teaching.tutor.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("tutor_session")
public class TutorSessionEntity {

    public static final String DEFAULT_TITLE = "新对话";

    @TableId
    private Long id;
    private Long courseId;
    private Long assistantId;
    private Long accountId;
    private String title;
    private String compressedSummary;
    private Long summaryUpToMsgId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    protected TutorSessionEntity() {
    }

    public TutorSessionEntity(long courseId, long assistantId, long accountId, LocalDateTime now) {
        this.courseId = courseId;
        this.assistantId = assistantId;
        this.accountId = accountId;
        this.title = DEFAULT_TITLE;
        this.compressedSummary = "";
        this.summaryUpToMsgId = 0L;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public Long getAssistantId() {
        return assistantId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCompressedSummary() {
        return compressedSummary;
    }

    public Long getSummaryUpToMsgId() {
        return summaryUpToMsgId;
    }

    public void updateSummary(String summary, long upToMsgId) {
        this.compressedSummary = summary;
        this.summaryUpToMsgId = upToMsgId;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
