package cn.utcy.teaching.knowledgegraph.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/**
 * 图谱节点:结构 = parent_id + position(同父兄弟从 1 连续编号,顶层 parent_id 为空);
 * 内容按 kind 分列(CHECK 约束保证互斥)——章节的摘要 / 知识点的释义 / 代码示例的说明;
 * 出处(source_section_title / quote)构建入库自动填,教师可改可清。别名以 JSON 数组文本存储,编解码在应用层。
 */
@TableName("knowledge_node")
public class KnowledgeNode {

    @TableId
    private Long id;
    private Long graphId;
    private Long parentId;
    private Integer position;
    private NodeKind kind;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private KpType kpType;
    private String label;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String summary;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String definition;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String explanation;
    @TableField(value = "aliases", updateStrategy = FieldStrategy.ALWAYS)
    private String aliasesJson;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String code;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String language;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String sourceSectionTitle;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String quote;
    private Instant createdAt;
    private Instant updatedAt;

    protected KnowledgeNode() {
    }

    public KnowledgeNode(long graphId, Long parentId, int position, NodeKind kind, KpType kpType, String label,
                         String summary, String definition, String explanation, String aliasesJson, String code,
                         String language, String sourceSectionTitle, String quote, Instant now) {
        this.graphId = graphId;
        this.parentId = parentId;
        this.position = position;
        this.kind = kind;
        this.kpType = kpType;
        this.label = label;
        this.summary = summary;
        this.definition = definition;
        this.explanation = explanation;
        this.aliasesJson = aliasesJson;
        this.code = code;
        this.language = language;
        this.sourceSectionTitle = sourceSectionTitle;
        this.quote = quote;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 改内容不改类型:kind 由创建时的上下文决定,之后不变 */
    public void update(KpType kpType, String label, String summary, String definition, String explanation,
                       String aliasesJson, String code, String language, String sourceSectionTitle, String quote,
                       Instant now) {
        this.kpType = kpType;
        this.label = label;
        this.summary = summary;
        this.definition = definition;
        this.explanation = explanation;
        this.aliasesJson = aliasesJson;
        this.code = code;
        this.language = language;
        this.sourceSectionTitle = sourceSectionTitle;
        this.quote = quote;
        this.updatedAt = now;
    }

    public String text() {
        if (summary != null) {
            return summary;
        }
        if (definition != null) {
            return definition;
        }
        return explanation;
    }

    public Long getId() {
        return id;
    }

    public Long getGraphId() {
        return graphId;
    }

    public Long getParentId() {
        return parentId;
    }

    public Integer getPosition() {
        return position;
    }

    public NodeKind getKind() {
        return kind;
    }

    public KpType getKpType() {
        return kpType;
    }

    public String getLabel() {
        return label;
    }

    public String getSummary() {
        return summary;
    }

    public String getDefinition() {
        return definition;
    }

    public String getExplanation() {
        return explanation;
    }

    public String getAliasesJson() {
        return aliasesJson;
    }

    public String getCode() {
        return code;
    }

    public String getLanguage() {
        return language;
    }

    public String getSourceSectionTitle() {
        return sourceSectionTitle;
    }

    public String getQuote() {
        return quote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
