package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProblemOutlineSourceProviderTest {
    private final ProgrammingProblemMapper problems = mock(ProgrammingProblemMapper.class);
    private final ProblemOutlineSourceProvider provider = new ProblemOutlineSourceProvider(problems);

    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ProgrammingProblem.class);
    }

    @Test
    void courseProblemWithConfirmedTestcaseIsLinkable() {
        when(problems.selectForUpdate(1L)).thenReturn(problem(11L, true));

        String title = provider.requireLinkable(11L, 1L);

        assertThat(title).isEqualTo("题目");
        verify(problems).selectForUpdate(1L);
        verify(problems, never()).selectById(1L);
    }

    @Test
    void courseProblemWithoutTestcaseIsRejected() {
        when(problems.selectForUpdate(1L)).thenReturn(problem(11L, false));

        assertThatThrownBy(() -> provider.requireLinkable(11L, 1L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("编程题尚未配置测试数据");
    }

    @Test
    void problemFromAnotherCourseIsNotFound() {
        when(problems.selectForUpdate(1L)).thenReturn(problem(12L, true));

        assertThatThrownBy(() -> provider.requireLinkable(11L, 1L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("课程中不存在该编程题");
    }

    @Test
    void requireTitlesFailsWhenAnyIdIsMissing() {
        when(problems.selectList(any())).thenReturn(List.of(problem(11L, true)));

        assertThat(provider.requireTitles(11L, Set.of(1L, 2L))).hasSize(1);
        assertThat(provider.requireTitles(11L, Set.of(1L))).containsEntry(1L, "题目");
        assertThat(provider.requireTitles(11L, Set.of())).isEmpty();
    }

    private ProgrammingProblem problem(long courseId, boolean testcaseConfirmed) {
        Instant now = Instant.parse("2026-07-24T00:00:00Z");
        return new ProgrammingProblem(
                1L,
                7L,
                courseId,
                "题目",
                "题面",
                ProblemDifficulty.EASY,
                1000,
                256,
                1024,
                "[\"CPP20\"]",
                testcaseConfirmed ? "a".repeat(64) : null,
                testcaseConfirmed ? 128L : null,
                testcaseConfirmed ? now : null,
                now,
                now);
    }
}
