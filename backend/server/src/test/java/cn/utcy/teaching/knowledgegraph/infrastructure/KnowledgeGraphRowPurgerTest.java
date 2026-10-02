package cn.utcy.teaching.knowledgegraph.infrastructure;

import cn.utcy.teaching.knowledgegraph.domain.KnowledgeEdge;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeGraph;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeNode;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeNodeResource;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeGraphRowPurgerTest {

    private final KnowledgeGraphMapper graphs = mock(KnowledgeGraphMapper.class);
    private final KnowledgeNodeMapper nodes = mock(KnowledgeNodeMapper.class);
    private final KnowledgeEdgeMapper edges = mock(KnowledgeEdgeMapper.class);
    private final KnowledgeNodeResourceMapper resources = mock(KnowledgeNodeResourceMapper.class);
    private final KnowledgeGraphBuildMapper builds = mock(KnowledgeGraphBuildMapper.class);
    private final BuildSectionMapper sections = mock(BuildSectionMapper.class);
    private final KnowledgeGraphRowPurger purger = new KnowledgeGraphRowPurger(
            graphs, nodes, edges, resources, builds, sections);

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeGraph.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeNode.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeEdge.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeNodeResource.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeGraphBuildEntity.class);
        TableInfoHelper.initTableInfo(assistant, BuildSectionEntity.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void contentRowsGoBeforeTheGraphRow() {
        purger.purgeGraphs(List.of(9L));

        InOrder order = inOrder(resources, edges, nodes, graphs);
        order.verify(resources).delete(any(Wrapper.class));
        order.verify(edges).delete(any(Wrapper.class));
        order.verify(nodes).delete(any(Wrapper.class));
        order.verify(graphs).delete(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildSectionsGoBeforeTheBuildRow() {
        purger.purgeBuilds(List.of(3L));

        InOrder order = inOrder(sections, builds);
        order.verify(sections).delete(any(Wrapper.class));
        order.verify(builds).delete(any(Wrapper.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void courseDeletionPurgesBuildsThenGraphs() {
        KnowledgeGraphBuildEntity build = mock(KnowledgeGraphBuildEntity.class);
        when(build.getId()).thenReturn(3L);
        when(builds.selectList(any(Wrapper.class))).thenReturn(List.of(build));
        KnowledgeGraph graph = mock(KnowledgeGraph.class);
        when(graph.getId()).thenReturn(9L);
        when(graphs.selectList(any(Wrapper.class))).thenReturn(List.of(graph));

        purger.purgeCourse(6L);

        InOrder order = inOrder(sections, builds, nodes, graphs);
        order.verify(sections).delete(any(Wrapper.class));
        order.verify(builds).delete(any(Wrapper.class));
        order.verify(nodes).delete(any(Wrapper.class));
        order.verify(graphs).delete(any(Wrapper.class));
    }
}
