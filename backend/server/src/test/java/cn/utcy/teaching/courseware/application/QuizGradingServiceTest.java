package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.infrastructure.QuizAttemptEntity;
import cn.utcy.teaching.courseware.infrastructure.QuizAttemptMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class QuizGradingServiceTest {

    private static final Actor STUDENT = new Actor(21L, "student", SystemRole.STUDENT);

    private final CoursewareApplicationService coursewares =
            mock(CoursewareApplicationService.class);
    private final QuizAttemptMapper attemptMapper = mock(QuizAttemptMapper.class);
    private final CourseAccess courseAccess = mock(CourseAccess.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final QuizGradingService service = new QuizGradingService(
            coursewares, attemptMapper, courseAccess, currentActor, new ObjectMapper(),
                mock(LearningEventRecorder.class));

    @BeforeEach
    void actor() {
        when(currentActor.require()).thenReturn(STUDENT);
    }

    private Stage stageWithQuiz() {
        Block quiz = new Block.QuizChoice("blk-quiz_choice-1", "2kg 物体受 6N 力,加速度是?",
                List.of(new Block.QuizOption("A", "3 m/s²"), new Block.QuizOption("B", "12 m/s²")),
                List.of("A"), false, "a = F/m = 3");
        Stage.Scene scene = new Stage.Scene("p1", "quiz", "测验", "quiz", null, List.of(quiz), List.of(), List.of(), null);
        return new Stage("课", "default", List.of(scene));
    }

    @Test
    void 非法选项标签400不入库() {
        when(coursewares.getInternal(9L, 10L)).thenReturn(stageWithQuiz());
        assertThatThrownBy(
                        () -> service.grade(9L, 10L, "p1", "blk-quiz_choice-1", List.of("Z")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Z");
        verify(attemptMapper, never()).insert(any(QuizAttemptEntity.class));
    }

    @Test
    void 答对时返回裁决并按学生落作答记录() {
        when(coursewares.getInternal(9L, 10L)).thenReturn(stageWithQuiz());

        QuizGradingService.Verdict verdict =
                service.grade(9L, 10L, "p1", "blk-quiz_choice-1", List.of("A"));

        assertThat(verdict.correct()).isTrue();
        assertThat(verdict.answer()).containsExactly("A");
        assertThat(verdict.explanation()).isEqualTo("a = F/m = 3");
        verify(courseAccess).requireLearningAccess(9L, STUDENT);

        ArgumentCaptor<QuizAttemptEntity> captor = ArgumentCaptor.forClass(QuizAttemptEntity.class);
        verify(attemptMapper).insert(captor.capture());
        assertThat(captor.getValue().getChosen()).isEqualTo("[\"A\"]");
        assertThat(captor.getValue().getCorrect()).isTrue();
        assertThat(captor.getValue().getAccountId()).isEqualTo(21L);
    }

    @Test
    void 多选题必须完全命中才算对() {
        Block quiz = new Block.QuizChoice("blk-quiz_choice-1", "多选",
                List.of(new Block.QuizOption("A", "甲"), new Block.QuizOption("B", "乙"),
                        new Block.QuizOption("C", "丙")),
                List.of("A", "C"), true, "讲解");
        Stage.Scene scene = new Stage.Scene("p1", "quiz", "测验", "quiz", null, List.of(quiz), List.of(), List.of(), null);
        when(coursewares.getInternal(9L, 10L))
                .thenReturn(new Stage("课", "default", List.of(scene)));

        assertThat(service.grade(9L, 10L, "p1", "blk-quiz_choice-1", List.of("A")).correct())
                .isFalse();
        assertThat(service.grade(9L, 10L, "p1", "blk-quiz_choice-1", List.of("C", "A")).correct())
                .isTrue();
    }

    @Test
    void 页或题不存在时报404() {
        when(coursewares.getInternal(9L, 10L)).thenReturn(stageWithQuiz());
        assertThatThrownBy(() -> service.grade(9L, 10L, "ghost", "blk-quiz_choice-1", List.of("A")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("页面不存在");
        assertThatThrownBy(() -> service.grade(9L, 10L, "p1", "blk-ghost", List.of("A")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("题目不存在");
        verify(attemptMapper, never()).insert(any(QuizAttemptEntity.class));
    }
}
