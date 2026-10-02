package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.programming.application.ProgrammingProblemApplicationService.ProblemDetailView;
import cn.utcy.teaching.programming.application.ProgrammingProblemApplicationService.ProblemDraft;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemProvenanceMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemSampleMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgrammingProblemAccessTest {
    private final ProgrammingProblemMapper problems = mock(ProgrammingProblemMapper.class);
    private final ProgrammingProblemSampleMapper samples = mock(ProgrammingProblemSampleMapper.class);
    private final ProgrammingProblemProvenanceMapper provenance = mock(ProgrammingProblemProvenanceMapper.class);
    private final CourseOutlineLinks courseOutlineLinks = mock(CourseOutlineLinks.class);
    private final CourseAccess courseAccess = mock(CourseAccess.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final ProgrammingProblemApplicationService service =
            new ProgrammingProblemApplicationService(
                    problems,
                    samples,
                    provenance,
                    mock(ProgrammingSubmissionMapper.class),
                    currentActor,
                    new ObjectMapper(),
                    courseOutlineLinks,
                    courseAccess,
                    mock(ProgrammingPurger.class),
                    List.of());
    private final Actor teacher = new Actor(7L, "teacher", SystemRole.TEACHER);
    private final Actor student = new Actor(9L, "student", SystemRole.STUDENT);

    @Test
    void courseManagerUpdatesProblemUnderRowLock() {
        when(currentActor.require()).thenReturn(teacher);
        when(problems.selectForUpdate(1L)).thenReturn(problem(6L));
        when(samples.selectList(any())).thenReturn(List.of());
        when(problems.updateById((ProgrammingProblem) any())).thenReturn(1);

        service.update(6L, 1L, draft());

        verify(courseAccess).requireManagementAccess(6L, teacher);
        verify(problems).updateById((ProgrammingProblem) any());
    }

    @Test
    void nonManagerCannotUpdateProblem() {
        when(currentActor.require()).thenReturn(student);
        doThrow(new ForbiddenOperationException("无权管理该课程"))
                .when(courseAccess).requireManagementAccess(6L, student);

        assertThatThrownBy(() -> service.update(6L, 1L, draft()))
                .isInstanceOf(ForbiddenOperationException.class);

        verify(problems, never()).selectForUpdate(anyLong());
    }

    @Test
    void problemOfAnotherCourseIsNotFoundUnderThisCourse() {
        when(currentActor.require()).thenReturn(teacher);
        when(problems.selectForUpdate(1L)).thenReturn(problem(5L));

        assertThatThrownBy(() -> service.update(6L, 1L, draft()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("课程中不存在该编程题");

        verify(problems, never()).updateById((ProgrammingProblem) any());
    }

    @Test
    void studentCannotReadProblemOutsideOutline() {
        when(currentActor.require()).thenReturn(student);
        when(courseOutlineLinks.isLinked(6L, CourseOutlineItemType.PROGRAMMING_PROBLEM, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.get(6L, 1L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("课程内容中不存在该编程题");

        verify(courseAccess).requireLearningAccess(6L, student);
        verify(courseAccess, never()).requireManagementAccess(anyLong(), any());
        verify(problems, never()).selectById(anyLong());
    }

    @Test
    void studentReadsLinkedProblem() {
        when(currentActor.require()).thenReturn(student);
        when(courseOutlineLinks.isLinked(6L, CourseOutlineItemType.PROGRAMMING_PROBLEM, 1L)).thenReturn(true);
        when(problems.selectById(1L)).thenReturn(problem(6L));
        when(samples.selectList(any())).thenReturn(List.of());
        when(provenance.selectById(1L)).thenReturn(null);

        ProblemDetailView detail = service.get(6L, 1L);

        assertThat(detail.problem().title()).isEqualTo("题目");
        assertThat(detail.problem().courseId()).isEqualTo(6L);
        assertThat(detail.samples()).isEmpty();
        assertThat(detail.provenance()).isNull();
        verify(courseAccess).requireLearningAccess(6L, student);
    }

    private static ProblemDraft draft() {
        return new ProblemDraft(
                "题目", "题面", ProblemDifficulty.EASY, 1000, 256, 1024, Set.of(ProgrammingLanguage.CPP20), List.of());
    }

    private static ProgrammingProblem problem(long courseId) {
        return new ProgrammingProblem(
                1L, 7L, courseId, "题目", "题面", ProblemDifficulty.EASY, 1000, 256, 1024,
                "[\"CPP20\"]", null, null, null, Instant.EPOCH, Instant.EPOCH);
    }
}
