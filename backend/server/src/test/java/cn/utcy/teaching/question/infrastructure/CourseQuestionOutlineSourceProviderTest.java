package cn.utcy.teaching.question.infrastructure;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.question.domain.CourseQuestion;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseQuestionOutlineSourceProviderTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CourseQuestion.class);
    }

    @Test
    void linkingLocksQuestionByCourseAndQuestionIdAndReturnsTitle() {
        CourseQuestionMapper questions = mock(CourseQuestionMapper.class);
        when(questions.selectForUpdate(20L, 10L)).thenReturn(question());
        CourseQuestionOutlineSourceProvider provider = provider(questions);

        String title = provider.requireLinkable(20L, 10L);

        assertThat(title).isEqualTo("课程试题");
        verify(questions).selectForUpdate(20L, 10L);
        verify(questions, never()).selectById(10L);
    }

    @Test
    void questionFromAnotherCourseCannotBeLinked() {
        CourseQuestionMapper questions = mock(CourseQuestionMapper.class);
        CourseQuestionOutlineSourceProvider provider = provider(questions);

        assertThatThrownBy(() -> provider.requireLinkable(20L, 10L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("课程中不存在该试题");

        verify(questions).selectForUpdate(20L, 10L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void titleResolutionIsScopedToCourse() {
        CourseQuestionMapper questions = mock(CourseQuestionMapper.class);
        CourseQuestionOutlineSourceProvider provider = provider(questions);
        when(questions.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(question()));

        Map<Long, String> titles = provider.requireTitles(20L, Set.of(10L));

        assertThat(titles).containsExactly(Map.entry(10L, "课程试题"));
        ArgumentCaptor<LambdaQueryWrapper<CourseQuestion>> queryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(questions).selectList(queryCaptor.capture());
        queryCaptor.getValue().getSqlSegment();
        assertThat(queryCaptor.getValue().getParamNameValuePairs().values()).contains(20L);
    }

    /** 缺失的 id 不在提供者层报错:由注册表统一当作不变量被破坏处理 */
    @Test
    @SuppressWarnings("unchecked")
    void titleResolutionReturnsOnlyExistingIds() {
        CourseQuestionMapper questions = mock(CourseQuestionMapper.class);
        CourseQuestionOutlineSourceProvider provider = provider(questions);
        when(questions.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(question()));

        assertThat(provider.requireTitles(20L, Set.of(10L, 11L))).containsOnlyKeys(10L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void questionWithoutItemsCannotBeLinked() {
        CourseQuestionMapper questions = mock(CourseQuestionMapper.class);
        CourseQuestionItemMapper items = mock(CourseQuestionItemMapper.class);
        when(questions.selectForUpdate(20L, 10L)).thenReturn(question());
        when(items.exists(any(LambdaQueryWrapper.class))).thenReturn(false);

        assertThatThrownBy(() -> new CourseQuestionOutlineSourceProvider(questions, items).requireLinkable(20L, 10L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("试题还没有题目，不能加入课程内容");
    }

    @SuppressWarnings("unchecked")
    private static CourseQuestionOutlineSourceProvider provider(
            CourseQuestionMapper questions
    ) {
        CourseQuestionItemMapper items = mock(CourseQuestionItemMapper.class);
        when(items.exists(any(LambdaQueryWrapper.class))).thenReturn(true);
        return new CourseQuestionOutlineSourceProvider(questions, items);
    }

    private static CourseQuestion question() {
        CourseQuestion question = CourseQuestion.create(20L, "课程试题", new CourseQuestion.Settings(null, true, true));
        try {
            var field = CourseQuestion.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(question, 10L);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        return question;
    }
}
