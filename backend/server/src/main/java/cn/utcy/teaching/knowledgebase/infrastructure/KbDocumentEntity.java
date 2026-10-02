package cn.utcy.teaching.knowledgebase.infrastructure;

import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.knowledgebase.domain.DocumentState;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 知识库文档:课程资料的入库快照;material_id 在资料删除后为 NULL(正文真源在 chunk 表)。
 * 运行态(parsing / indexing)的行由直启入库任务执行:run_token 是本次运行的写围栏,
 * progress_heartbeat_at 由进展心跳更新——停更超时由判滞巡检判失败,教师可重试。
 */
@TableName("knowledge_base_document")
public class KbDocumentEntity {

    @TableId
    private Long id;
    private Long knowledgeBaseId;
    private Long materialId;
    private String name;
    private DocumentState state;
    private String errorMessage;
    private Integer chunkCount;
    private String signature;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String runToken;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private LocalDateTime progressHeartbeatAt;

    protected KbDocumentEntity() {
    }

    public KbDocumentEntity(long knowledgeBaseId, long materialId, String name,
                            LocalDateTime now) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.materialId = materialId;
        this.name = name;
        this.state = DocumentState.PENDING;
        this.chunkCount = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void start(String runToken, LocalDateTime now) {
        this.state = DocumentState.PARSING;
        this.runToken = runToken;
        this.progressHeartbeatAt = now;
        this.errorMessage = null;
        this.updatedAt = now;
    }

    public void indexing(LocalDateTime now) {
        this.state = DocumentState.INDEXING;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return state == DocumentState.PARSING || state == DocumentState.INDEXING;
    }

    public void markReady(int chunkCount, String signature, LocalDateTime now) {
        this.errorMessage = null;
        this.chunkCount = chunkCount;
        this.signature = signature;
        settle(DocumentState.READY, now);
    }

    public void markError(String message, LocalDateTime now) {
        this.errorMessage = Text.truncate(message, 1000);
        settle(DocumentState.ERROR, now);
    }

    /** 离开运行态:run 标记与心跳清空,迟到的旧线程从此写不进任何东西 */
    private void settle(DocumentState target, LocalDateTime now) {
        this.state = target;
        this.runToken = null;
        this.progressHeartbeatAt = null;
        this.updatedAt = now;
    }

    public void updateSignature(String signature, LocalDateTime now) {
        this.signature = signature;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getKnowledgeBaseId() {
        return knowledgeBaseId;
    }

    public Long getMaterialId() {
        return materialId;
    }

    public String getName() {
        return name;
    }

    public DocumentState getState() {
        return state;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Integer getChunkCount() {
        return chunkCount;
    }

    public String getSignature() {
        return signature;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public String getRunToken() {
        return runToken;
    }

    public LocalDateTime getProgressHeartbeatAt() {
        return progressHeartbeatAt;
    }
}
