package cn.utcy.teaching.knowledgebase.application;

import cn.utcy.teaching.shared.run.StaleRunCleaner;
import cn.utcy.teaching.knowledgebase.domain.DocumentState;
import cn.utcy.teaching.knowledgebase.infrastructure.KbDocumentEntity;
import cn.utcy.teaching.knowledgebase.infrastructure.KbDocumentMapper;
import cn.utcy.teaching.knowledgebase.infrastructure.KnowledgebaseProperties;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Component
class StaleDocumentIngestCleaner extends StaleRunCleaner {

    static final String INTERRUPTED = "入库中断（服务重启或长时间无进展），可重试";

    private final KbDocumentMapper documents;
    private final TransactionOperations transactions;

    StaleDocumentIngestCleaner(KbDocumentMapper documents, KnowledgebaseProperties properties,
                               TransactionOperations transactions, Clock clock) {
        super("知识库入库", properties.progressTimeout(), clock);
        this.documents = documents;
        this.transactions = transactions;
    }

    @Override
    protected List<Long> candidates(LocalDateTime staleBefore) {
        LambdaQueryWrapper<KbDocumentEntity> query = new LambdaQueryWrapper<KbDocumentEntity>()
                .select(KbDocumentEntity::getId)
                .in(KbDocumentEntity::getState, DocumentState.PARSING, DocumentState.INDEXING);
        if (staleBefore != null) {
            query.and(inner -> inner.isNull(KbDocumentEntity::getProgressHeartbeatAt)
                    .or().lt(KbDocumentEntity::getProgressHeartbeatAt, staleBefore));
        }
        return documents.selectList(query).stream().map(KbDocumentEntity::getId).toList();
    }

    @Override
    protected String judge(long documentId, LocalDateTime staleBefore) {
        return transactions.execute(status -> {
            KbDocumentEntity locked = documents.selectForUpdate(documentId);
            if (locked == null || !locked.isActive() || fresh(locked.getProgressHeartbeatAt(), staleBefore)) {
                return null;
            }
            locked.markError(INTERRUPTED, now());
            documents.updateById(locked);
            return INTERRUPTED;
        });
    }
}
