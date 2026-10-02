package cn.utcy.teaching.analytics.infrastructure;

import cn.utcy.teaching.shared.learning.LearningEvent;
import cn.utcy.teaching.shared.learning.LearningEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 落库必须开自己的 REQUIRES_NEW 写事务:afterCommit 里原事务连接仍绑定在线程上,REQUIRED 会参与那个
 * 已提交(且可能只读)的事务,资料打开这类只读调用方的事件会静默丢失。
 */
class LearningEventRecorderImplTest {

    @Test
    void persistsInAFreshWritableTransaction() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        LearningEventMapper events = mock(LearningEventMapper.class);
        LearningEventRecorderImpl recorder = new LearningEventRecorderImpl(events, new ObjectMapper(), manager);

        recorder.record(new LearningEvent(6L, 9L, LearningEventType.QUESTION_ATTEMPTED, 12L, Map.of()));

        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getValue().isReadOnly()).isFalse();
        verify(events).insert(any(LearningEventEntity.class));
        verify(manager).commit(any());
    }
}
