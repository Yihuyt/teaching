package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphEditor.NodeCreated;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.GraphSnapshot;
import cn.utcy.teaching.knowledgegraph.domain.BuildPreview;
import cn.utcy.teaching.knowledgegraph.domain.EdgeKind;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeEdge;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeGraph;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeNode;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeNodeResource;
import cn.utcy.teaching.knowledgegraph.domain.KpType;
import cn.utcy.teaching.knowledgegraph.domain.NodeContent;
import cn.utcy.teaching.knowledgegraph.domain.NodeKind;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeEdgeMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphRowPurger;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeNodeMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeNodeResourceMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.NodeResourceSources;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeGraphEditorTest {

    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

    private final KnowledgeGraphMapper graphs = mock(KnowledgeGraphMapper.class);
    private final KnowledgeNodeMapper nodes = mock(KnowledgeNodeMapper.class);
    private final KnowledgeEdgeMapper edges = mock(KnowledgeEdgeMapper.class);
    private final KnowledgeNodeResourceMapper resources = mock(KnowledgeNodeResourceMapper.class);
    private final NodeResourceSources sources = mock(NodeResourceSources.class);
    private final KnowledgeGraphChangeMarker changes = mock(KnowledgeGraphChangeMarker.class);
    private final List<KnowledgeNode> stored = new ArrayList<>();
    private final List<KnowledgeEdge> storedEdges = new ArrayList<>();
    private final List<KnowledgeNodeResource> storedResources = new ArrayList<>();
    private final KnowledgeGraph graph = new KnowledgeGraph(9L, 6L, "光学", true, NOW, NOW);
    private final AtomicLong ids = new AtomicLong(100);
    private KnowledgeGraphEditor editor;

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeGraph.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeNode.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeEdge.class);
        TableInfoHelper.initTableInfo(assistant, KnowledgeNodeResource.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(graphs.selectForUpdate(6L, 9L)).thenReturn(graph);
        when(graphs.insert(any(KnowledgeGraph.class))).thenAnswer(invocation -> {
            setId(invocation.getArgument(0), "id", 9L);
            return 1;
        });
        when(nodes.selectList(any(Wrapper.class))).thenAnswer(invocation -> List.copyOf(stored));
        when(edges.selectList(any(Wrapper.class))).thenAnswer(invocation -> List.copyOf(storedEdges));
        when(resources.selectList(any(Wrapper.class))).thenAnswer(invocation -> List.copyOf(storedResources));
        when(nodes.insert(any(KnowledgeNode.class))).thenAnswer(invocation -> {
            KnowledgeNode node = invocation.getArgument(0);
            setId(node, "id", ids.incrementAndGet());
            stored.add(node);
            return 1;
        });
        when(edges.insert(any(KnowledgeEdge.class))).thenAnswer(invocation -> {
            KnowledgeEdge edge = invocation.getArgument(0);
            setId(edge, "id", ids.incrementAndGet());
            storedEdges.add(edge);
            return 1;
        });
        when(resources.insert(any(KnowledgeNodeResource.class))).thenReturn(1);
        when(nodes.updateById(any(KnowledgeNode.class))).thenReturn(1);
        when(edges.deleteById(anyLong())).thenReturn(1);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AliasesCodec codec = new AliasesCodec(new ObjectMapper());
        KnowledgeGraphService service = new KnowledgeGraphService(graphs, nodes, edges, resources, sources,
                mock(CourseMaterialApplicationService.class), mock(CourseOutlineLinks.class),
                mock(LearningEventRecorder.class), mock(CourseAccess.class), mock(CurrentActor.class), codec,
                mock(KnowledgeGraphRowPurger.class), clock);
        editor = new KnowledgeGraphEditor(graphs, nodes, edges, resources, sources, service, changes, codec,
                mock(CourseAccess.class), mock(CurrentActor.class), clock);
    }

    private KnowledgeNode node(long id, Long parentId, int position, NodeKind kind, String label) {
        KnowledgeNode node = new KnowledgeNode(9L, parentId, position, kind,
                kind == NodeKind.KNOWLEDGE_POINT ? KpType.CONCEPT : null, label, null, null, null, null,
                kind == NodeKind.CODE_EXAMPLE ? "x" : null, kind == NodeKind.CODE_EXAMPLE ? "py" : null,
                null, null, NOW);
        setId(node, "id", id);
        stored.add(node);
        return node;
    }

    @Test
    @DisplayName("新建节点:先锁图谱行,追加到父节点末尾,成功后 touch + 发事件,返回新节点 id 与快照")
    void createNodeAppendsAndPublishes() {
        node(1, null, 1, NodeKind.UNIT, "第一章");
        node(2, 1L, 1, NodeKind.KNOWLEDGE_POINT, "已有知识点");

        NodeCreated created = editor.createNode(6L, 9L, 1L,
                NodeContent.knowledgePoint("变量", KpType.CONCEPT, "释义", List.of("var"), null, null));

        InOrder order = inOrder(graphs, nodes, changes);
        order.verify(graphs).selectForUpdate(6L, 9L);
        order.verify(nodes).insert(any(KnowledgeNode.class));
        order.verify(changes).changed(graph);
        KnowledgeNode inserted = stored.get(stored.size() - 1);
        assertThat(inserted.getPosition()).isEqualTo(2);
        assertThat(inserted.getParentId()).isEqualTo(1L);
        assertThat(inserted.getAliasesJson()).isEqualTo("[\"var\"]");
        assertThat(created.createdNodeId()).isEqualTo(inserted.getId());
        assertThat(created.snapshot().nodes()).hasSize(3);
    }

    @Test
    @DisplayName("父节点已不存在 → 404「节点已被删除,请刷新」,不写任何东西")
    void missingParentIs404() {
        assertThatThrownBy(() -> editor.createNode(6L, 9L, 42L, NodeContent.unit("章", null)))
                .isInstanceOf(NotFoundException.class).hasMessage("节点已被删除，请刷新");
        verify(nodes, never()).insert(any(KnowledgeNode.class));
        verify(changes, never()).changed(any());
    }

    @Test
    @DisplayName("删除节点级联子树、触及的关系与挂载,并补齐原兄弟位置")
    @SuppressWarnings("unchecked")
    void deleteNodeCascades() {
        node(1, null, 1, NodeKind.UNIT, "章");
        node(2, 1L, 1, NodeKind.KNOWLEDGE_POINT, "点");
        node(3, 2L, 1, NodeKind.CODE_EXAMPLE, "例");
        node(4, null, 2, NodeKind.UNIT, "后一章");

        editor.deleteNode(6L, 9L, 1L);

        InOrder order = inOrder(resources, edges, nodes, changes);
        order.verify(resources).delete(any(Wrapper.class));
        order.verify(edges).delete(any(Wrapper.class));
        order.verify(nodes).delete(any(Wrapper.class));
        order.verify(nodes).closeGap(9L, 0L, 1, 1L);
        order.verify(changes).changed(graph);
    }

    @Test
    @DisplayName("前置成环被拒(409)并给出环路;同一对已有关系时提示先删除;相关关系按 id 序规范化存储")
    void edgesValidatedAndNormalized() {
        node(1, null, 1, NodeKind.KNOWLEDGE_POINT, "A");
        node(2, null, 2, NodeKind.KNOWLEDGE_POINT, "B");
        node(3, null, 3, NodeKind.KNOWLEDGE_POINT, "C");
        edge(1L, 2L, EdgeKind.PREREQUISITE);
        edge(2L, 3L, EdgeKind.PREREQUISITE);

        assertThatThrownBy(() -> editor.createEdge(6L, 9L, 3L, 1L, EdgeKind.PREREQUISITE))
                .isInstanceOf(ConflictException.class).hasMessage("会形成循环前置：C → A → B → C");
        assertThatThrownBy(() -> editor.createEdge(6L, 9L, 2L, 1L, EdgeKind.PREREQUISITE))
                .isInstanceOf(ConflictException.class).hasMessageContaining("已有前置关系");

        editor.createEdge(6L, 9L, 3L, 1L, EdgeKind.RELATED);
        KnowledgeEdge related = storedEdges.get(storedEdges.size() - 1);
        assertThat(related.getSourceNodeId()).isEqualTo(1L);
        assertThat(related.getTargetNodeId()).isEqualTo(3L);
        assertThat(related.getKind()).isEqualTo(EdgeKind.RELATED);
    }

    @Test
    @DisplayName("挂载:先锁内容行(requireLinkable)再锁图谱行;重复挂载 409")
    void attachResourceLocksContentFirst() {
        node(1, null, 1, NodeKind.KNOWLEDGE_POINT, "点");

        editor.attachResource(6L, 9L, 1L, CourseOutlineItemType.QUESTION, 20L);

        InOrder order = inOrder(sources, graphs, resources, changes);
        order.verify(sources).requireLinkable(6L, CourseOutlineItemType.QUESTION, 20L);
        order.verify(graphs).selectForUpdate(6L, 9L);
        order.verify(resources).insert(any(KnowledgeNodeResource.class));
        order.verify(changes).changed(graph);

        storedResources.add(new KnowledgeNodeResource(9L, 1L, CourseOutlineItemType.QUESTION, 20L, NOW));
        assertThatThrownBy(() -> editor.attachResource(6L, 9L, 1L, CourseOutlineItemType.QUESTION, 20L))
                .isInstanceOf(ConflictException.class).hasMessage("该资源已挂在此节点上");
    }

    @Test
    @DisplayName("构建入库:父先子后写入、临时键换成 id、相关关系规范化,整图校验后发事件")
    void createFromPreviewWritesTree() {
        BuildPreview preview = new BuildPreview(List.of(
                new BuildPreview.PreviewNode("u0", null, 1, NodeKind.UNIT, null, "第一章", "摘要", null, null,
                        List.of(), null, null, null, null),
                new BuildPreview.PreviewNode("k0", "u0", 1, NodeKind.KNOWLEDGE_POINT, KpType.CONCEPT, "变量",
                        null, "释义", null, List.of("var"), null, null, "1.1 甲节", "引文"),
                new BuildPreview.PreviewNode("k1", "u0", 2, NodeKind.KNOWLEDGE_POINT, KpType.METHOD, "赋值",
                        null, null, null, List.of(), null, null, "1.1 甲节", null),
                new BuildPreview.PreviewNode("c0", "k0", 1, NodeKind.CODE_EXAMPLE, null, "示例", null, null,
                        "说明", List.of(), "x = 1", "python", null, null)),
                List.of(new BuildPreview.PreviewEdge("k1", "k0", EdgeKind.RELATED, "证据"),
                        new BuildPreview.PreviewEdge("k0", "k1", EdgeKind.PREREQUISITE, null)),
                List.of());

        editor.createFromPreview(6L, "光学", preview);

        assertThat(stored).extracting(KnowledgeNode::getLabel).containsExactly("第一章", "变量", "赋值", "示例");
        KnowledgeNode code = stored.get(3);
        assertThat(code.getParentId()).isEqualTo(stored.get(1).getId());
        assertThat(stored.get(1).getQuote()).isEqualTo("引文");
        assertThat(storedEdges).hasSize(2);
        KnowledgeEdge related = storedEdges.get(0);
        assertThat(related.getSourceNodeId()).isLessThan(related.getTargetNodeId());
        verify(changes).changed(any(KnowledgeGraph.class));
    }

    @Test
    @DisplayName("重命名节点:只改内容,快照里位置不变")
    void updateNodeKeepsStructure() {
        node(1, null, 1, NodeKind.UNIT, "章");
        node(2, 1L, 1, NodeKind.KNOWLEDGE_POINT, "旧名");

        GraphSnapshot snapshot = editor.updateNode(6L, 9L, 2L,
                new NodeContent(null, "新名", KpType.RULE, null, null, null, List.of(), null, null, null, null));

        assertThat(snapshot.nodes()).filteredOn(view -> view.id() == 2L)
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.label()).isEqualTo("新名");
                    assertThat(view.kpType()).isEqualTo(KpType.RULE);
                    assertThat(view.position()).isEqualTo(1);
                });
        verify(changes).changed(graph);
    }

    private void edge(long source, long target, EdgeKind kind) {
        KnowledgeEdge edge = new KnowledgeEdge(9L, source, target, kind, null, NOW);
        setId(edge, "id", ids.incrementAndGet());
        storedEdges.add(edge);
    }

    private static void setId(Object target, String field, long id) {
        try {
            var declared = target.getClass().getDeclaredField(field);
            declared.setAccessible(true);
            declared.set(target, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
