package cn.utcy.teaching.tutor.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("tutor_message")
public class TutorMessageEntity {

    @TableId
    private Long id;
    private Long sessionId;
    private String role;
    private String content;
    private String sourcesJson;
    private String traceJson;
    private LocalDateTime createdAt;

    protected TutorMessageEntity() {
    }

    public TutorMessageEntity(long sessionId, String role, String content, String sourcesJson, String traceJson,
                              LocalDateTime createdAt) {
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.sourcesJson = sourcesJson;
        this.traceJson = traceJson;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getSourcesJson() {
        return sourcesJson;
    }

    public String getTraceJson() {
        return traceJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
