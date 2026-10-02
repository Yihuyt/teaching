package cn.utcy.teaching.knowledgebase.infrastructure;

import cn.utcy.teaching.shared.course.KnowledgeBaseDeletionGuard;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 知识库及其从属行的显式删除(数据库不设外键):文档 → 切片;知识库 → 文档、其他模块的挂载
 * (经 KnowledgeBaseDeletionGuard)。删库 / 删文档 / 删课程共用,必须在业务事务内调用。
 * ES 物理索引在事务提交后清理(失败只记日志):外部索引不随数据库事务回滚,
 * 回滚时索引必须原样保留,ES 故障也不能挡住删除。
 */
@Component
public class KnowledgeBaseRowPurger {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseRowPurger.class);

    private final KnowledgeBaseMapper knowledgeBases;
    private final KbDocumentMapper documents;
    private final KbChunkMapper chunks;
    private final EsClient es;
    private final List<KnowledgeBaseDeletionGuard> deletionGuards;

    KnowledgeBaseRowPurger(KnowledgeBaseMapper knowledgeBases, KbDocumentMapper documents, KbChunkMapper chunks,
                           EsClient es, List<KnowledgeBaseDeletionGuard> deletionGuards) {
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.chunks = chunks;
        this.es = es;
        this.deletionGuards = List.copyOf(deletionGuards);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeDocuments(List<Long> documentIds) {
        if (documentIds.isEmpty()) {
            return;
        }
        chunks.delete(new LambdaQueryWrapper<KbChunkEntity>().in(KbChunkEntity::getDocumentId, documentIds));
        documents.delete(new LambdaQueryWrapper<KbDocumentEntity>().in(KbDocumentEntity::getId, documentIds));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeKnowledgeBases(List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds.isEmpty()) {
            return;
        }
        List<KnowledgeBaseEntity> owned = knowledgeBases.selectList(new LambdaQueryWrapper<KnowledgeBaseEntity>()
                .in(KnowledgeBaseEntity::getId, knowledgeBaseIds));
        for (Long knowledgeBaseId : knowledgeBaseIds) {
            deletionGuards.forEach(guard -> guard.beforeKnowledgeBaseDeleted(knowledgeBaseId));
        }
        purgeDocuments(documents.selectList(new LambdaQueryWrapper<KbDocumentEntity>()
                        .select(KbDocumentEntity::getId)
                        .in(KbDocumentEntity::getKnowledgeBaseId, knowledgeBaseIds))
                .stream()
                .map(KbDocumentEntity::getId)
                .toList());
        knowledgeBases.delete(new LambdaQueryWrapper<KnowledgeBaseEntity>()
                .in(KnowledgeBaseEntity::getId, knowledgeBaseIds));
        List<String> indexNames = owned.stream()
                .map(KnowledgeBaseEntity::getActiveIndexName)
                .filter(name -> name != null)
                .toList();
        if (!indexNames.isEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    for (String indexName : indexNames) {
                        try {
                            es.deleteIndexIfExists(indexName);
                        } catch (RuntimeException exception) {
                            log.warn("知识库索引 {} 清理失败,留待人工处理", indexName, exception);
                        }
                    }
                }
            });
        }
    }
}
