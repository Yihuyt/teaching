package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.identity.application.AccountDirectory;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.infrastructure.JudgeJobMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import cn.utcy.teaching.programming.infrastructure.SubmissionCaseResultMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LearnerRoleParityTest {
    @BeforeAll
    static void initializeTableMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ProgrammingSubmission.class);
    }

    @ParameterizedTest
    @EnumSource(SystemRole.class)
    void mySubmissionsAreAlwaysScopedToTheCurrentAccount(SystemRole role) {
        Actor actor = new Actor(17L, role.value(), role);
        ProgrammingProblemApplicationService problems = mock(ProgrammingProblemApplicationService.class);
        when(problems.requireLearnableProblem(6L, 1L, false)).thenReturn(new ProgrammingProblem(
                1L, 7L, 6L, "题目", "题面", ProblemDifficulty.EASY, 1000, 256, 1024,
                "[\"CPP20\"]", null, null, null, Instant.EPOCH, Instant.EPOCH));
        ProgrammingSubmissionMapper submissions = mock(ProgrammingSubmissionMapper.class);
        when(submissions.selectList(any())).thenReturn(List.of());
        SubmissionApplicationService service = new SubmissionApplicationService(
                problems,
                submissions,
                mock(SubmissionCaseResultMapper.class),
                mock(JudgeJobMapper.class),
                mock(AccountDirectory.class),
                () -> actor,
                mock(CourseAccess.class));

        service.listMine(6L, 1L);

        ArgumentCaptor<LambdaQueryWrapper<ProgrammingSubmission>> query = wrapperCaptor();
        verify(submissions).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("account_id");
        assertThat(query.getValue().getParamNameValuePairs().values()).contains(17L);
    }

    @SuppressWarnings("unchecked")
    private <T> ArgumentCaptor<LambdaQueryWrapper<T>> wrapperCaptor() {
        return ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }
}
