package cn.utcy.teaching.knowledgegraph.domain;

import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 节点上挂载的课程内容(文件 / 试题 / 编程题):只存引用,标题读取时向内容模块取;
 * 内容删除时由 CourseContentDeletionGuard 在同一事务内卸载。graph_id 冗余供按图谱清理与学情快照。
 */
@TableName("knowledge_node_resource")
public class KnowledgeNodeResource {

    @TableId
    private Long id;
    private Long graphId;
    private Long nodeId;
    private CourseOutlineItemType itemType;
    private Long contentId;
    private Instant createdAt;

    protected KnowledgeNodeResource() {
    }

    public KnowledgeNodeResource(long graphId, long nodeId, CourseOutlineItemType itemType, long contentId,
                                 Instant now) {
        this.graphId = graphId;
        this.nodeId = nodeId;
        this.itemType = itemType;
        this.contentId = contentId;
        this.createdAt = now;
    }

    public Long getId() {
        return id;
    }

    public Long getGraphId() {
        return graphId;
    }

    public Long getNodeId() {
        return nodeId;
    }

    public CourseOutlineItemType getItemType() {
        return itemType;
    }

    public Long getContentId() {
        return contentId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
