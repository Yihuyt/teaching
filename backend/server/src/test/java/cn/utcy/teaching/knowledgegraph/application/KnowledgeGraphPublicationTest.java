package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeGraph;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeEdgeMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphRowPurger;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeNodeMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeNodeResourceMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.NodeResourceSources;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeGraphPublicationTest {

    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    private final KnowledgeGraphMapper graphs = mock(KnowledgeGraphMapper.class);
    private final LearningEventRecorder learningEvents = mock(LearningEventRecorder.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private KnowledgeGraphService service;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeGraph.class);
    }

    @BeforeEach
    void setUp() {
        when(currentActor.require()).thenReturn(new Actor(7L, "student", SystemRole.STUDENT));
        when(graphs.updateById(any(KnowledgeGraph.class))).thenReturn(1);
        service = new KnowledgeGraphService(graphs, mock(KnowledgeNodeMapper.class), mock(KnowledgeEdgeMapper.class),
                mock(KnowledgeNodeResourceMapper.class), mock(NodeResourceSources.class),
                mock(CourseMaterialApplicationService.class), mock(CourseOutlineLinks.class),
                learningEvents, mock(CourseAccess.class), currentActor, new AliasesCodec(new ObjectMapper()),
                mock(KnowledgeGraphRowPurger.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private KnowledgeGraph graph(boolean published) {
        return new KnowledgeGraph(9L, 6L, "光学", published, NOW, NOW);
    }

    @Test
    @DisplayName("发布 / 取消发布:锁行改状态;重复发布、重复取消 409")
    void publishAndUnpublish() {
        when(graphs.selectForUpdate(6L, 9L)).thenReturn(graph(false));
        assertThat(service.publish(6L, 9L).published()).isTrue();
        when(graphs.selectForUpdate(6L, 9L)).thenReturn(graph(true));
        assertThatThrownBy(() -> service.publish(6L, 9L))
                .isInstanceOf(ConflictException.class).hasMessage("知识图谱已发布");

        assertThat(service.unpublish(6L, 9L).published()).isFalse();
        when(graphs.selectForUpdate(6L, 9L)).thenReturn(graph(false));
        assertThatThrownBy(() -> service.unpublish(6L, 9L))
                .isInstanceOf(ConflictException.class).hasMessage("知识图谱未发布");
    }

    @Test
    @DisplayName("学生读未发布图谱 → 404,文案与不存在一致,不记学习事件;教师照常可读")
    void unpublishedGraphHiddenFromStudents() {
        when(graphs.selectOne(any())).thenReturn(graph(false));

        assertThatThrownBy(() -> service.snapshot(6L, 9L, false))
                .isInstanceOf(NotFoundException.class).hasMessage("当前课程中不存在该知识图谱");
        verify(learningEvents, never()).record(any());

        assertThat(service.snapshot(6L, 9L, true).graph().published()).isFalse();
    }
}
