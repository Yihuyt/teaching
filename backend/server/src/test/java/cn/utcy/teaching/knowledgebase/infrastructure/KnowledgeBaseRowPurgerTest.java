package cn.utcy.teaching.knowledgebase.infrastructure;

import cn.utcy.teaching.shared.course.KnowledgeBaseDeletionGuard;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseRowPurgerTest {

    private final KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
    private final KbDocumentMapper documents = mock(KbDocumentMapper.class);
    private final KbChunkMapper chunks = mock(KbChunkMapper.class);
    private final KnowledgeBaseDeletionGuard guard = mock(KnowledgeBaseDeletionGuard.class);
    private final EsClient es = mock(EsClient.class);
    private final KnowledgeBaseRowPurger purger =
            new KnowledgeBaseRowPurger(knowledgeBases, documents, chunks, es, List.of(guard));

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseEntity.class);
        TableInfoHelper.initTableInfo(assistant, KbDocumentEntity.class);
        TableInfoHelper.initTableInfo(assistant, KbChunkEntity.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void guardsRunFirstThenChunksDocumentsAndTheKnowledgeBaseRow() {
        KbDocumentEntity document = mock(KbDocumentEntity.class);
        when(document.getId()).thenReturn(5L);
        when(documents.selectList(any(Wrapper.class))).thenReturn(List.of(document));

        purger.purgeKnowledgeBases(List.of(3L));

        InOrder order = inOrder(guard, chunks, documents, knowledgeBases);
        order.verify(guard).beforeKnowledgeBaseDeleted(3L);
        order.verify(chunks).delete(any(Wrapper.class));
        order.verify(documents).delete(any(Wrapper.class));
        order.verify(knowledgeBases).delete(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void esIndexDeletedOnlyAfterCommitAndFailureOnlyLogs() {
        KnowledgeBaseEntity kb = mock(KnowledgeBaseEntity.class);
        when(kb.getId()).thenReturn(3L);
        when(kb.getActiveIndexName()).thenReturn("kb-3-sig-x");
        when(knowledgeBases.selectList(any(Wrapper.class))).thenReturn(List.of(kb));
        when(documents.selectList(any(Wrapper.class))).thenReturn(List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("es down")).when(es).deleteIndexIfExists("kb-3-sig-x");

        TransactionSynchronizationManager.initSynchronization();
        try {
            purger.purgeKnowledgeBases(List.of(3L));
            // 提交前:一次 ES 调用都没有(回滚时索引必须原样保留)
            org.mockito.Mockito.verifyNoInteractions(es);
            // 提交后:删索引;ES 故障只记日志,不外抛
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            org.mockito.Mockito.verify(es).deleteIndexIfExists("kb-3-sig-x");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
