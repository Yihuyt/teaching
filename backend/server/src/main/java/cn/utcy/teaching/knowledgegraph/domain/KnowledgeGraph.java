package cn.utcy.teaching.knowledgegraph.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 图谱本身就是树的根:顶层节点 parent 为空。updated_at 即内容版本——任何节点 / 关系 / 挂载变更都 touch 它。
 */
@TableName("knowledge_graph")
public class KnowledgeGraph {

    @TableId
    private Long id;
    private Long courseId;
    private String name;
    private boolean published;
    private Instant createdAt;
    private Instant updatedAt;

    protected KnowledgeGraph() {
    }

    public KnowledgeGraph(Long id, Long courseId, String name, boolean published,
                          Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.courseId = courseId;
        this.name = name;
        this.published = published;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    /** 新图谱(含入库产出)默认未发布:教师确认内容后发布,学生才可见 */
    public static KnowledgeGraph create(long courseId, String name, Instant now) {
        return new KnowledgeGraph(null, courseId, name, false, now, now);
    }

    /** 发布只改可见性,不是内容变更:不 touch updated_at */
    public void publish() {
        if (published) {
            throw new IllegalStateException("知识图谱已发布");
        }
        this.published = true;
    }

    public void unpublish() {
        if (!published) {
            throw new IllegalStateException("知识图谱未发布");
        }
        this.published = false;
    }

    public void rename(String name, Instant now) {
        this.name = name;
        this.updatedAt = now;
    }

    public void touch(Instant now) {
        this.updatedAt = now;
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
