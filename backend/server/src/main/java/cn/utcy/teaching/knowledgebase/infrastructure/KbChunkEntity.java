package cn.utcy.teaching.knowledgebase.infrastructure;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** 知识库分块正文(真源):来源展示与重建索引都从这里读,ES 只存检索副本 */
@TableName("knowledge_base_chunk")
public class KbChunkEntity {

    @TableId
    private Long id;
    private Long documentId;
    private Integer seq;
    private String section;
    private String content;

    protected KbChunkEntity() {
    }

    public KbChunkEntity(long documentId, int seq, String section, String content) {
        this.documentId = documentId;
        this.seq = seq;
        this.section = section;
        this.content = content;
    }

    public Long getId() {
        return id;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public Integer getSeq() {
        return seq;
    }

    public String getSection() {
        return section;
    }

    public String getContent() {
        return content;
    }
}
