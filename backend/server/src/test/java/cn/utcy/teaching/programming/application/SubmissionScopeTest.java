package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.identity.application.AccountDirectory;
import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.infrastructure.JudgeJobMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemProvenanceMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemSampleMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import cn.utcy.teaching.programming.infrastructure.SubmissionCaseResultMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SubmissionScopeTest {
    private final ProgrammingProblemMapper problems = mock(ProgrammingProblemMapper.class);
    private final ProgrammingSubmissionMapper submissions = mock(ProgrammingSubmissionMapper.class);
    private final JudgeJobMapper jobs = mock(JudgeJobMapper.class);
    private final CourseOutlineLinks courseOutlineLinks = mock(CourseOutlineLinks.class);
    private final CourseAccess courseAccess = mock(CourseAccess.class);
    private final CurrentActor currentActor = () -> new Actor(9L, "student", SystemRole.STUDENT);
    private final ProgrammingProblemApplicationService problemService =
            new ProgrammingProblemApplicationService(
                    problems,
                    mock(ProgrammingProblemSampleMapper.class),
                    mock(ProgrammingProblemProvenanceMapper.class),
                    submissions,
                    currentActor,
                    new ObjectMapper(),
                    courseOutlineLinks,
                    courseAccess,
                    mock(ProgrammingPurger.class),
                    List.of());
    private final SubmissionApplicationService service = new SubmissionApplicationService(
            problemService,
            submissions,
            mock(SubmissionCaseResultMapper.class),
            jobs,
            mock(AccountDirectory.class),
            currentActor,
            mock(CourseAccess.class));

    @Test
    void studentCannotSubmitProblemOutsideOutline() {
        when(courseOutlineLinks.isLinked(6L, CourseOutlineItemType.PROGRAMMING_PROBLEM, 1L)).thenReturn(false);

        assertThatThrownBy(() -> service.submit(6L, 1L, ProgrammingLanguage.CPP20, "int main() {}"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("课程内容中不存在该编程题");

        verify(courseAccess).requireLearningAccess(anyLong(), any());
        verify(problems, never()).selectForUpdate(anyLong());
        verify(submissions, never()).insert((ProgrammingSubmission) any());
        verify(jobs, never()).insert((JudgeJob) any());
    }

    /** 课程内容项永远就绪,测试数据却可能已被删:提交时明确拒绝,而不是判题阶段失败 */
    @Test
    void submitRejectsProblemWithoutConfirmedTestcase() {
        when(courseOutlineLinks.isLinked(6L, CourseOutlineItemType.PROGRAMMING_PROBLEM, 1L)).thenReturn(true);
        when(problems.selectForUpdate(1L)).thenReturn(problem(null));

        assertThatThrownBy(() -> service.submit(6L, 1L, ProgrammingLanguage.CPP20, "int main() {}"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("编程题尚未配置测试数据");

        verify(courseAccess).requireLearningAccess(6L, currentActor.require());
        verify(submissions, never()).insert((ProgrammingSubmission) any());
        verify(jobs, never()).insert((JudgeJob) any());
    }

    private static ProgrammingProblem problem(String testcaseSha256) {
        Instant now = Instant.parse("2026-07-23T00:00:00Z");
        return new ProgrammingProblem(
                1L, 7L, 6L, "题目", "题面", ProblemDifficulty.EASY, 1000, 256, 1024,
                "[\"CPP20\"]", testcaseSha256,
                testcaseSha256 == null ? null : 128L,
                testcaseSha256 == null ? null : now,
                now, now);
    }
}
