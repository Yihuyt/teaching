package cn.utcy.teaching.tutor.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseService;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseService.KnowledgeBaseMount;
import cn.utcy.teaching.tutor.infrastructure.TutorAssistantEntity;
import cn.utcy.teaching.tutor.infrastructure.TutorAssistantEntity.ModelSettings;
import cn.utcy.teaching.tutor.infrastructure.TutorAssistantMapper;
import cn.utcy.teaching.tutor.infrastructure.TutorProperties;
import cn.utcy.teaching.tutor.infrastructure.TutorRowPurger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TutorAssistantServiceTest {

    private final TutorAssistantMapper assistants = mock(TutorAssistantMapper.class);
    private final KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
    private final CourseAccess courseAccess = mock(CourseAccess.class);
    private final TutorProperties properties =
            new TutorProperties("test", 0.2, 0.9, 8000, false, 8, 100_000);
    private final TutorAssistantService service = new TutorAssistantService(assistants, knowledgeBases,
            courseAccess, mock(CurrentActor.class), properties, mock(TutorRowPurger.class));

    private TutorAssistantEntity assistant(boolean visibleToStudents) {
        TutorAssistantEntity entity = new TutorAssistantEntity(6L, "答疑助手", "", null,
                new ModelSettings("qwen-max", 0.2, false, 8), visibleToStudents,
                LocalDateTime.parse("2026-08-31T00:00:00"));
        try {
            var field = TutorAssistantEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, 9L);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        when(assistants.selectById(9L)).thenReturn(entity);
        return entity;
    }

    @Test
    @DisplayName("课程管理者可用未开放的助手;普通成员只可用开放的(未开放对其等同不存在)")
    void requireUsableRules() {
        Actor teacher = mock(Actor.class);
        Actor student = mock(Actor.class);
        assistant(false);
        when(courseAccess.canManage(6L, teacher)).thenReturn(true);
        when(courseAccess.canManage(6L, student)).thenReturn(false);

        assertThat(service.requireUsable(6L, 9L, teacher)).isNotNull();
        assertThatThrownBy(() -> service.requireUsable(6L, 9L, student))
                .isInstanceOf(NotFoundException.class);

        assistant(true);
        assertThat(service.requireUsable(6L, 9L, student)).isNotNull();
    }

    @Test
    @DisplayName("对话挂载只取索引就绪的知识库;未就绪的不进工具也不预检索")
    void mountsFilterToReadyKnowledgeBases() {
        TutorAssistantEntity entity = assistant(true);
        when(assistants.knowledgeBaseIds(entity.getId())).thenReturn(List.of(1L, 2L));
        when(knowledgeBases.describe(6L, List.of(1L, 2L))).thenReturn(List.of(
                new KnowledgeBaseMount(1L, "就绪库", true),
                new KnowledgeBaseMount(2L, "待重建库", false)));

        TutorMounts mounts = service.mounts(entity);

        assertThat(mounts.knowledgeBases()).singleElement()
                .satisfies(kb -> assertThat(kb.name()).isEqualTo("就绪库"));
        assertThat(mounts.model().providerModel()).isEqualTo("qwen-max");
        assertThat(mounts.maxRounds()).isEqualTo(8);
    }
}
