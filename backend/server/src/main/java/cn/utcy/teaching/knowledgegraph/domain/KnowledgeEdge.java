package cn.utcy.teaching.knowledgegraph.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** 知识点之间的一条语义关系;相关关系按 (min id → max id) 规范化存储,见 {@link GraphRules#normalizeRelated} */
@TableName("knowledge_edge")
public class KnowledgeEdge {

    @TableId
    private Long id;
    private Long graphId;
    private Long sourceNodeId;
    private Long targetNodeId;
    private EdgeKind kind;
    /** 构建时模型给出的关系依据(≤ 500 字);手工建立的关系为空 */
    private String evidence;
    private Instant createdAt;

    protected KnowledgeEdge() {
    }

    public KnowledgeEdge(long graphId, long sourceNodeId, long targetNodeId, EdgeKind kind, String evidence,
                         Instant now) {
        this.graphId = graphId;
        this.sourceNodeId = sourceNodeId;
        this.targetNodeId = targetNodeId;
        this.kind = kind;
        this.evidence = evidence;
        this.createdAt = now;
    }

    public boolean touches(long nodeId) {
        return sourceNodeId == nodeId || targetNodeId == nodeId;
    }

    public Long getId() {
        return id;
    }

    public Long getGraphId() {
        return graphId;
    }

    public Long getSourceNodeId() {
        return sourceNodeId;
    }

    public Long getTargetNodeId() {
        return targetNodeId;
    }

    public EdgeKind getKind() {
        return kind;
    }

    public String getEvidence() {
        return evidence;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
