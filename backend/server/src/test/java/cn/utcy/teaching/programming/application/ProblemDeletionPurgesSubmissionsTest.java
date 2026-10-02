package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.course.CourseContentDeletionGuard;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemProvenanceMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemSampleMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProblemDeletionPurgesSubmissionsTest {
    private final ProgrammingProblemMapper problems = mock(ProgrammingProblemMapper.class);
    private final CourseOutlineLinks courseOutlineLinks = mock(CourseOutlineLinks.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final ProgrammingPurger purger = mock(ProgrammingPurger.class);
    private final CourseContentDeletionGuard deletionGuard = mock(CourseContentDeletionGuard.class);
    private final ProgrammingProblemApplicationService service =
            new ProgrammingProblemApplicationService(
                    problems,
                    mock(ProgrammingProblemSampleMapper.class),
                    mock(ProgrammingProblemProvenanceMapper.class),
                    mock(ProgrammingSubmissionMapper.class),
                    currentActor,
                    new ObjectMapper(),
                    courseOutlineLinks,
                    mock(CourseAccess.class),
                    purger,
                    List.of(deletionGuard));

    @Test
    void deletingProblemsUnlinksAndNotifiesGuardsPerProblemThenPurgesOnce() {
        when(currentActor.require()).thenReturn(new Actor(7L, "teacher", SystemRole.TEACHER));
        when(problems.selectForUpdate(1L)).thenReturn(problem(1L));
        when(problems.selectForUpdate(2L)).thenReturn(problem(2L));

        service.delete(6L, List.of(1L, 2L));

        InOrder order = inOrder(courseOutlineLinks, deletionGuard, purger);
        order.verify(courseOutlineLinks).unlink(6L, CourseOutlineItemType.PROGRAMMING_PROBLEM, 1L);
        order.verify(deletionGuard).beforeContentDeleted(6L, CourseOutlineItemType.PROGRAMMING_PROBLEM, 1L);
        order.verify(courseOutlineLinks).unlink(6L, CourseOutlineItemType.PROGRAMMING_PROBLEM, 2L);
        order.verify(deletionGuard).beforeContentDeleted(6L, CourseOutlineItemType.PROGRAMMING_PROBLEM, 2L);
        order.verify(purger).purgeProblems(List.of(1L, 2L));
        verify(courseOutlineLinks, never()).isLinked(anyLong(), any(), anyLong());
        verify(problems, never()).deleteById(anyLong());
    }

    private static ProgrammingProblem problem(long id) {
        return new ProgrammingProblem(
                id, 7L, 6L, "题目", "题面", ProblemDifficulty.EASY, 1000, 256, 1024,
                "[\"CPP20\"]", null, null, null, Instant.EPOCH, Instant.EPOCH);
    }
}
