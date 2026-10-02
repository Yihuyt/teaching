package cn.utcy.teaching.tutor.infrastructure;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TutorRowPurgerTest {

    private final TutorAssistantMapper assistants = mock(TutorAssistantMapper.class);
    private final TutorSessionMapper sessions = mock(TutorSessionMapper.class);
    private final TutorMessageMapper messages = mock(TutorMessageMapper.class);
    private final TutorRowPurger purger = new TutorRowPurger(assistants, sessions, messages);

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, TutorAssistantEntity.class);
        TableInfoHelper.initTableInfo(assistant, TutorSessionEntity.class);
        TableInfoHelper.initTableInfo(assistant, TutorMessageEntity.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void purgesMessagesSessionsMountsThenTheAssistantRow() {
        TutorSessionEntity session = mock(TutorSessionEntity.class);
        when(session.getId()).thenReturn(40L);
        when(sessions.selectList(any(Wrapper.class))).thenReturn(List.of(session));

        purger.purgeAssistant(4L);

        InOrder order = inOrder(messages, sessions, assistants);
        order.verify(messages).delete(any(Wrapper.class));
        order.verify(sessions).delete(any(Wrapper.class));
        order.verify(assistants).clearKnowledgeBases(4L);
        order.verify(assistants).deleteById(4L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void courseDeletionPurgesEveryAssistantOfTheCourse() {
        TutorAssistantEntity first = mock(TutorAssistantEntity.class);
        when(first.getId()).thenReturn(4L);
        TutorAssistantEntity second = mock(TutorAssistantEntity.class);
        when(second.getId()).thenReturn(5L);
        when(assistants.selectList(any(Wrapper.class))).thenReturn(List.of(first, second));
        when(sessions.selectList(any(Wrapper.class))).thenReturn(List.of());

        purger.purgeCourse(6L);

        InOrder order = inOrder(assistants);
        order.verify(assistants).deleteById(4L);
        order.verify(assistants).deleteById(5L);
    }
}
