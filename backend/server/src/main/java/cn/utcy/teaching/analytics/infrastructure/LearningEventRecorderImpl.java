package cn.utcy.teaching.analytics.infrastructure;

import cn.utcy.teaching.shared.learning.LearningEvent;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 根级 LearningEventRecorder 的唯一实现:直插 learning_event。
 * 业务事务内调用时挂到 afterCommit——业务回滚不会留下幽灵事件;
 * 落库用自己的 REQUIRES_NEW 写事务:afterCommit 里原事务的连接仍绑定在线程上,REQUIRED 会"参与"那个
 * 已提交的事务——只读调用方(如资料下载)的连接还是 READ ONLY,插入直接失败;写调用方则靠连接清理时的
 * 隐式提交侥幸落库。REQUIRES_NEW 另取连接、自己提交,两种调用方都确定落库。失败只记日志,埋点永远不砸中业务操作。
 */
@Component
class LearningEventRecorderImpl implements LearningEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(LearningEventRecorderImpl.class);

    private final LearningEventMapper events;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    LearningEventRecorderImpl(LearningEventMapper events, ObjectMapper objectMapper,
                              PlatformTransactionManager transactionManager) {
        this.events = events;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transactions.setReadOnly(false);
    }

    @Override
    public void record(LearningEvent event) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    persistSafely(event);
                }
            });
            return;
        }
        persistSafely(event);
    }

    private void persistSafely(LearningEvent event) {
        try {
            String detail = event.detail() == null || event.detail().isEmpty()
                    ? null : objectMapper.writeValueAsString(event.detail());
            transactions.executeWithoutResult(status -> events.insert(new LearningEventEntity(
                    event.courseId(),
                    event.accountId(),
                    event.type().value(),
                    event.type().objectType(),
                    event.objectId(),
                    detail,
                    LocalDateTime.now(ZoneOffset.UTC))));
        } catch (JsonProcessingException | RuntimeException exception) {
            log.error("学习事件记录失败（不影响业务操作）：type={}，courseId={}，accountId={}",
                    event.type(), event.courseId(), event.accountId(), exception);
        }
    }
}
