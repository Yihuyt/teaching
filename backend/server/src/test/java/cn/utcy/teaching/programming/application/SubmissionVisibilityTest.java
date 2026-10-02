package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.identity.application.AccountDirectory;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.domain.SubmissionStatus;
import cn.utcy.teaching.programming.infrastructure.JudgeJobMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import cn.utcy.teaching.programming.infrastructure.SubmissionCaseResultMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionVisibilityTest {
    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");

    private final ProgrammingProblemApplicationService problems = mock(ProgrammingProblemApplicationService.class);
    private final ProgrammingSubmissionMapper submissions = mock(ProgrammingSubmissionMapper.class);
    private final SubmissionCaseResultMapper caseResults = mock(SubmissionCaseResultMapper.class);
    private final CourseAccess courseAccess = mock(CourseAccess.class);

    @BeforeEach
    void stub() {
        when(problems.requireProblem(6L, 8L)).thenReturn(new ProgrammingProblem(
                8L, 7L, 6L, "题目", "题面", ProblemDifficulty.EASY, 1000, 256, 1024,
                "[\"CPP20\"]", null, null, null, NOW, NOW));
        when(submissions.selectById(31L)).thenReturn(new ProgrammingSubmission(
                31L, 8L, 17L, ProgrammingLanguage.CPP20, "int main() {}",
                SubmissionStatus.ACCEPTED, 10, 1024, null, null, NOW, NOW, NOW));
        when(caseResults.selectList(any())).thenReturn(List.of());
    }

    @Test
    void ownerReadsOwnSubmissionAsCourseMember() {
        Actor me = new Actor(17L, "me", SystemRole.STUDENT);

        SubmissionApplicationService.SubmissionDetailView detail = service(me).get(6L, 8L, 31L);

        assertThat(detail.submission().id()).isEqualTo(31L);
        assertThat(detail.submission().courseId()).isEqualTo(6L);
        assertThat(detail.submission().problemTitle()).isEqualTo("题目");
        verify(courseAccess).requireLearningAccess(6L, me);
        verify(courseAccess, never()).requireManagementAccess(anyLong(), any());
    }

    @Test
    void courseManagerReadsAnyMembersSubmission() {
        Actor teacher = new Actor(7L, "teacher", SystemRole.TEACHER);

        assertThat(service(teacher).get(6L, 8L, 31L).submission().id()).isEqualTo(31L);

        verify(courseAccess).requireManagementAccess(6L, teacher);
    }

    @Test
    void anotherMemberCannotReadIt() {
        Actor other = new Actor(18L, "other", SystemRole.STUDENT);
        doThrow(new ForbiddenOperationException("无权管理该课程"))
                .when(courseAccess).requireManagementAccess(6L, other);

        assertThatThrownBy(() -> service(other).get(6L, 8L, 31L))
                .isInstanceOf(ForbiddenOperationException.class);
    }

    @Test
    void submissionOfAnotherProblemIsNotFound() {
        when(problems.requireProblem(6L, 9L)).thenReturn(new ProgrammingProblem(
                9L, 7L, 6L, "别的题", "题面", ProblemDifficulty.EASY, 1000, 256, 1024,
                "[\"CPP20\"]", null, null, null, NOW, NOW));

        assertThatThrownBy(() -> service(new Actor(17L, "me", SystemRole.STUDENT)).get(6L, 9L, 31L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("提交记录不存在");
    }

    private SubmissionApplicationService service(Actor actor) {
        return new SubmissionApplicationService(
                problems, submissions, caseResults, mock(JudgeJobMapper.class),
                mock(AccountDirectory.class), () -> actor, courseAccess);
    }
}
