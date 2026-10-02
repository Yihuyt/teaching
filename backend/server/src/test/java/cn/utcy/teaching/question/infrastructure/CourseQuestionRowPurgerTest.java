package cn.utcy.teaching.question.infrastructure;

import cn.utcy.teaching.question.domain.CourseQuestion;
import cn.utcy.teaching.question.domain.CourseQuestionAttempt;
import cn.utcy.teaching.question.domain.CourseQuestionItem;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CourseQuestionRowPurgerTest {

    private final CourseQuestionMapper questions = mock(CourseQuestionMapper.class);
    private final CourseQuestionItemMapper items = mock(CourseQuestionItemMapper.class);
    private final CourseQuestionAttemptMapper attempts = mock(CourseQuestionAttemptMapper.class);
    private final CourseQuestionRowPurger purger = new CourseQuestionRowPurger(questions, items, attempts);

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CourseQuestion.class);
        TableInfoHelper.initTableInfo(assistant, CourseQuestionItem.class);
        TableInfoHelper.initTableInfo(assistant, CourseQuestionAttempt.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void purgesAttemptsAndItemsBeforeTheQuestionRow() {
        purger.purgeQuestions(List.of(10L, 11L));

        InOrder order = inOrder(attempts, items, questions);
        order.verify(attempts).delete(any(Wrapper.class));
        order.verify(items).delete(any(Wrapper.class));
        order.verify(questions).delete(any(Wrapper.class));
    }

    @Test
    void nothingHappensForNoQuestions() {
        purger.purgeQuestions(List.of());

        verify(attempts, never()).delete(any());
        verify(questions, never()).delete(any());
    }
}
