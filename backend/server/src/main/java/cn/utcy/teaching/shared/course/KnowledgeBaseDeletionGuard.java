package cn.utcy.teaching.shared.course;

/**
 * 知识库删除前其他模块的善后:挂载了该知识库的行(智能助教挂载)由各自模块显式删除。
 * 在删除知识库的同一事务内、删除知识库行之前调用。
 */
public interface KnowledgeBaseDeletionGuard {

    void beforeKnowledgeBaseDeleted(long knowledgeBaseId);
}
