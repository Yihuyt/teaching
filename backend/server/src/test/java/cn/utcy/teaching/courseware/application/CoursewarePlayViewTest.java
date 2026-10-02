package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage;
import cn.utcy.teaching.courseware.infrastructure.CoursewareEntity;
import cn.utcy.teaching.courseware.infrastructure.CoursewareMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** play 视图契约:quiz 块的答案与讲解绝不下发到播放端;未发布的课件对学生等同不存在 */
class CoursewarePlayViewTest {

    private static final Actor STUDENT = new Actor(21L, "student", SystemRole.STUDENT);

    private final CoursewareMapper mapper = mock(CoursewareMapper.class);
    private final CourseAccess courseAccess = mock(CourseAccess.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final ObjectMapper objectMapper =
            new ObjectMapper();
    private final StageJsonCodec codec = new StageJsonCodec(objectMapper);
    private final CoursewareApplicationService service = new CoursewareApplicationService(
            mapper, codec, mock(CoursewareAssetStorage.class), courseAccess, currentActor,
            mock(cn.utcy.teaching.courseware.infrastructure.CoursewareRowPurger.class));

    private CoursewareEntity row() {
        Block quiz = new Block.QuizChoice("blk-quiz_choice-1", "题干",
                List.of(new Block.QuizOption("A", "甲"), new Block.QuizOption("B", "乙")),
                List.of("B"), false, "因为乙是对的");
        Stage.Scene quizScene = new Stage.Scene("p1", "quiz", "测验", "quiz", null, List.of(quiz),
                List.of(new Stage.SpeechSegment("做题吧。", List.of(), null)), List.of(), null);
        Stage stage = new Stage("课", "default", List.of(quizScene));
        CoursewareEntity entity = mock(CoursewareEntity.class);
        when(entity.getId()).thenReturn(10L);
        when(entity.getSceneCount()).thenReturn(1);
        when(entity.getTitle()).thenReturn("课");
        when(entity.getBody()).thenReturn(codec.toJson(stage));
        when(entity.isPublished()).thenReturn(true);
        return entity;
    }

    @Test
    void play视图剥除答案与讲解_序列化后无答案痕迹() {
        when(currentActor.require()).thenReturn(STUDENT);
        CoursewareEntity entity = row();
        when(mapper.selectOne(any())).thenReturn(entity);

        JsonNode stage = service.getPlayView(9L, 10L).stage();
        JsonNode playQuiz = stage.path("scenes").path(0).path("blocks").path(0);
        assertThat(playQuiz.path("answer")).isEmpty();
        assertThat(playQuiz.path("explanation").asText()).isEmpty();
        assertThat(playQuiz.path("options")).hasSize(2);
        assertThat(playQuiz.path("stem").asText()).isEqualTo("题干");
        // 序列化后的 play JSON 里不出现正确答案的痕迹
        assertThat(stage.toString()).doesNotContain("因为乙是对的");
    }

    @Test
    void 未发布的课件学生不可播放_404文案与不存在一致() {
        when(currentActor.require()).thenReturn(STUDENT);
        CoursewareEntity entity = row();
        when(entity.isPublished()).thenReturn(false);
        when(mapper.selectOne(any())).thenReturn(entity);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.getPlayView(9L, 10L))
                .isInstanceOf(cn.utcy.teaching.shared.error.NotFoundException.class).hasMessage("课件不存在");
    }

}
