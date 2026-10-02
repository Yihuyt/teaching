package cn.utcy.teaching.knowledgebase.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/** 课程知识库:文档集合;active_signature 标记当前 ES 索引的 embedding 签名。挂载与问答提示词归课程助手(tutor) */
@TableName("knowledge_base")
public class KnowledgeBaseEntity {

    @TableId
    private Long id;
    private Long courseId;
    private String name;
    private String activeSignature;
    private String activeIndexName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    protected KnowledgeBaseEntity() {
    }

    public KnowledgeBaseEntity(long courseId, String name, LocalDateTime now) {
        this.courseId = courseId;
        this.name = name;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, LocalDateTime now) {
        this.name = name;
        this.updatedAt = now;
    }

    public void activateIndex(String signature, String indexName, LocalDateTime now) {
        this.activeSignature = signature;
        this.activeIndexName = indexName;
        this.updatedAt = now;
    }

    public String getActiveIndexName() {
        return activeIndexName;
    }

    public Long getId() {
        return id;
    }

    public Long getCourseId() {
        return courseId;
    }

    public String getName() {
        return name;
    }

    public String getActiveSignature() {
        return activeSignature;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
