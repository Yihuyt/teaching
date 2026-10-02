package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.learning.LearningEvent;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.learning.LearningEventType;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.knowledgegraph.domain.EdgeKind;
import cn.utcy.teaching.knowledgegraph.domain.GraphModel;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeEdge;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeGraph;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeNode;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeNodeResource;
import cn.utcy.teaching.knowledgegraph.domain.KpType;
import cn.utcy.teaching.knowledgegraph.domain.NodeKind;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeEdgeMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphRowPurger;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeNodeMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeNodeResourceMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.NodeResourceSources;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识图谱的读侧与图谱级 CRUD:图谱 / 节点 / 关系 / 挂载全部存 MySQL;
 * 一次读取装成 {@link GraphModel} 再组视图。内容写操作在 {@link KnowledgeGraphEditor}。
 * analyticsSnapshot 供学情模块读取——不做访问控制,调用方自行鉴权。
 */
@Service
public class KnowledgeGraphService {

    private final KnowledgeGraphMapper graphs;
    private final KnowledgeNodeMapper nodes;
    private final KnowledgeEdgeMapper edges;
    private final KnowledgeNodeResourceMapper resources;
    private final NodeResourceSources sources;
    private final CourseMaterialApplicationService materials;
    private final CourseOutlineLinks outlineLinks;
    private final LearningEventRecorder learningEvents;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final AliasesCodec aliases;
    private final KnowledgeGraphRowPurger purger;
    private final Clock clock;

    public KnowledgeGraphService(KnowledgeGraphMapper graphs, KnowledgeNodeMapper nodes, KnowledgeEdgeMapper edges,
                                 KnowledgeNodeResourceMapper resources, NodeResourceSources sources,
                                 CourseMaterialApplicationService materials, CourseOutlineLinks outlineLinks,
                                 LearningEventRecorder learningEvents, CourseAccess courseAccess,
                                 CurrentActor currentActor, AliasesCodec aliases, KnowledgeGraphRowPurger purger,
                                 Clock clock) {
        this.graphs = graphs;
        this.nodes = nodes;
        this.edges = edges;
        this.resources = resources;
        this.sources = sources;
        this.materials = materials;
        this.outlineLinks = outlineLinks;
        this.learningEvents = learningEvents;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.aliases = aliases;
        this.purger = purger;
        this.clock = clock;
    }

    // ---- 图谱 ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<GraphView> list(long courseId, boolean management) {
        requireAccess(courseId, management);
        return graphs.selectList(new LambdaQueryWrapper<KnowledgeGraph>()
                        .eq(KnowledgeGraph::getCourseId, courseId)
                        .eq(!management, KnowledgeGraph::isPublished, true)
                        .orderByDesc(KnowledgeGraph::getUpdatedAt))
                .stream()
                .map(this::view)
                .toList();
    }

    @Transactional
    public GraphView create(long courseId, String name) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraph graph = KnowledgeGraph.create(courseId, name.strip(), clock.instant());
        try {
            requireMutation(graphs.insert(graph), "知识图谱创建未生效");
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("同一课程内的知识图谱名称不能重复");
        }
        return view(graph);
    }

    @Transactional
    public GraphView rename(long courseId, long graphId, String name) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraph graph = requireGraphForUpdate(courseId, graphId);
        graph.rename(name.strip(), clock.instant());
        try {
            requireMutation(graphs.updateById(graph), "知识图谱状态已变化，更新未生效");
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("同一课程内的知识图谱名称不能重复");
        }
        return view(graph);
    }

    @Transactional
    public GraphView publish(long courseId, long graphId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraph graph = requireGraphForUpdate(courseId, graphId);
        if (graph.isPublished()) {
            throw new ConflictException("知识图谱已发布");
        }
        graph.publish();
        requireMutation(graphs.updateById(graph), "知识图谱状态已变化，发布未生效");
        return view(graph);
    }

    @Transactional
    public GraphView unpublish(long courseId, long graphId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraph graph = requireGraphForUpdate(courseId, graphId);
        if (!graph.isPublished()) {
            throw new ConflictException("知识图谱未发布");
        }
        graph.unpublish();
        requireMutation(graphs.updateById(graph), "知识图谱状态已变化，取消发布未生效");
        return view(graph);
    }

    @Transactional
    public void delete(long courseId, long graphId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        requireGraphForUpdate(courseId, graphId);
        purger.purgeGraphs(List.of(graphId));
    }

    @Transactional(readOnly = true)
    public GraphSnapshot snapshot(long courseId, long graphId, boolean management) {
        KnowledgeGraph graph = requireGraph(courseId, graphId);
        requireAccess(courseId, management);
        requireVisible(graph, management);
        GraphSnapshot snapshot = snapshot(graph, loadModel(graphId));
        if (!management) {
            learningEvents.record(new LearningEvent(courseId, currentActor.require().userId(),
                    LearningEventType.KG_VIEWED, graphId, Map.of()));
        }
        return snapshot;
    }

    /**
     * 打开节点挂载的资源:核验挂载后,文件给下载票(学生走课程内容门禁),试题 / 编程题只核验
     * 学生可见(在课程内容中),跳转由前端完成;学生记 kg_resource_opened。
     */
    @Transactional(readOnly = true)
    public ResourceOpenView openResource(long courseId, long graphId, long nodeId, CourseOutlineItemType itemType,
                                         long contentId, boolean management) {
        KnowledgeGraph graph = requireGraph(courseId, graphId);
        requireAccess(courseId, management);
        requireVisible(graph, management);
        KnowledgeNodeResource attached = resources.selectOne(new LambdaQueryWrapper<KnowledgeNodeResource>()
                .eq(KnowledgeNodeResource::getGraphId, graphId)
                .eq(KnowledgeNodeResource::getNodeId, nodeId)
                .eq(KnowledgeNodeResource::getItemType, itemType)
                .eq(KnowledgeNodeResource::getContentId, contentId));
        if (attached == null) {
            throw new NotFoundException("该节点未挂载此资源");
        }
        String downloadUrl = null;
        if (itemType == CourseOutlineItemType.MATERIAL) {
            downloadUrl = (management
                    ? materials.createDownloadTrusted(courseId, contentId)
                    : materials.createDownload(courseId, contentId)).url();
        } else if (!management && !outlineLinks.isLinked(courseId, itemType, contentId)) {
            throw new NotFoundException("课程内容中不存在该" + itemLabel(itemType));
        }
        if (!management) {
            learningEvents.record(new LearningEvent(courseId, currentActor.require().userId(),
                    LearningEventType.KG_RESOURCE_OPENED, graphId,
                    Map.of("nodeId", nodeId, "itemType", itemType.value(), "contentId", contentId)));
        }
        return new ResourceOpenView(itemType, contentId, downloadUrl);
    }

    // ---- 供学情模块的可信读取 ----------------------------------------------------

    public record SnapshotResource(CourseOutlineItemType itemType, long contentId, String title) {
    }

    public record SnapshotNode(long graphId, String graphName, long nodeId, String label, NodeKind kind,
                               List<SnapshotResource> resources) {
    }

    /** source 是 target 的前置 */
    public record PrerequisiteEdge(long graphId, long sourceNodeId, long targetNodeId) {
    }

    public record AnalyticsSnapshot(List<SnapshotNode> nodes, List<PrerequisiteEdge> prerequisiteEdges) {
    }

    @Transactional(readOnly = true)
    public AnalyticsSnapshot analyticsSnapshot(long courseId) {
        List<SnapshotNode> snapshotNodes = new ArrayList<>();
        List<PrerequisiteEdge> prerequisiteEdges = new ArrayList<>();
        for (KnowledgeGraph graph : graphs.selectList(new LambdaQueryWrapper<KnowledgeGraph>()
                .eq(KnowledgeGraph::getCourseId, courseId)
                .orderByAsc(KnowledgeGraph::getId))) {
            GraphModel model = loadModel(graph.getId());
            Map<Long, List<NodeResourceView>> resourceViews = resourceViews(courseId, model);
            for (KnowledgeNode node : model.nodes()) {
                snapshotNodes.add(new SnapshotNode(graph.getId(), graph.getName(), node.getId(), node.getLabel(),
                        node.getKind(), resourceViews.getOrDefault(node.getId(), List.of()).stream()
                        .map(r -> new SnapshotResource(r.itemType(), r.contentId(), r.title()))
                        .toList()));
            }
            for (KnowledgeEdge edge : model.edges()) {
                if (edge.getKind() == EdgeKind.PREREQUISITE) {
                    prerequisiteEdges.add(new PrerequisiteEdge(graph.getId(), edge.getSourceNodeId(),
                            edge.getTargetNodeId()));
                }
            }
        }
        return new AnalyticsSnapshot(snapshotNodes, prerequisiteEdges);
    }

    // ---- 与编辑器共用 ------------------------------------------------------------

    KnowledgeGraph requireGraph(long courseId, long graphId) {
        KnowledgeGraph graph = graphs.selectOne(new LambdaQueryWrapper<KnowledgeGraph>()
                .eq(KnowledgeGraph::getCourseId, courseId)
                .eq(KnowledgeGraph::getId, graphId));
        if (graph == null) {
            throw new NotFoundException("当前课程中不存在该知识图谱");
        }
        return graph;
    }

    /** 学生只能看到已发布的图谱;文案与不存在一致,不泄露存在性 */
    private static void requireVisible(KnowledgeGraph graph, boolean management) {
        if (!management && !graph.isPublished()) {
            throw new NotFoundException("当前课程中不存在该知识图谱");
        }
    }

    KnowledgeGraph requireGraphForUpdate(long courseId, long graphId) {
        KnowledgeGraph graph = graphs.selectForUpdate(courseId, graphId);
        if (graph == null) {
            throw new NotFoundException("当前课程中不存在该知识图谱");
        }
        return graph;
    }

    GraphModel loadModel(long graphId) {
        return new GraphModel(
                nodes.selectList(new LambdaQueryWrapper<KnowledgeNode>()
                        .eq(KnowledgeNode::getGraphId, graphId)
                        .orderByAsc(KnowledgeNode::getParentId)
                        .orderByAsc(KnowledgeNode::getPosition)),
                edges.selectList(new LambdaQueryWrapper<KnowledgeEdge>()
                        .eq(KnowledgeEdge::getGraphId, graphId)
                        .orderByAsc(KnowledgeEdge::getId)),
                resources.selectList(new LambdaQueryWrapper<KnowledgeNodeResource>()
                        .eq(KnowledgeNodeResource::getGraphId, graphId)
                        .orderByAsc(KnowledgeNodeResource::getId)));
    }

    GraphSnapshot snapshot(KnowledgeGraph graph, GraphModel model) {
        Map<Long, List<NodeResourceView>> resourceViews = resourceViews(graph.getCourseId(), model);
        List<NodeView> nodeViews = new ArrayList<>();
        for (KnowledgeNode node : model.nodes()) {
            nodeViews.add(new NodeView(node.getId(), node.getParentId(), node.getPosition(), node.getKind(),
                    node.getKpType(), node.getLabel(), node.getSummary(), node.getDefinition(), node.getExplanation(),
                    aliases.read(node.getAliasesJson()), node.getCode(), node.getLanguage(),
                    node.getSourceSectionTitle(), node.getQuote(),
                    resourceViews.getOrDefault(node.getId(), List.of())));
        }
        List<EdgeView> edgeViews = model.edges().stream()
                .map(edge -> new EdgeView(edge.getId(), edge.getSourceNodeId(), edge.getTargetNodeId(),
                        edge.getKind(), edge.getEvidence()))
                .toList();
        return new GraphSnapshot(view(graph), nodeViews, edgeViews);
    }

    private Map<Long, List<NodeResourceView>> resourceViews(long courseId, GraphModel model) {
        Map<CourseOutlineItemType, Set<Long>> idsByType = new EnumMap<>(CourseOutlineItemType.class);
        List<KnowledgeNodeResource> all = new ArrayList<>();
        for (KnowledgeNode node : model.nodes()) {
            for (KnowledgeNodeResource resource : model.resources(node.getId())) {
                idsByType.computeIfAbsent(resource.getItemType(), key -> new HashSet<>()).add(resource.getContentId());
                all.add(resource);
            }
        }
        Map<CourseOutlineItemType, Map<Long, String>> titles = new EnumMap<>(CourseOutlineItemType.class);
        idsByType.forEach((type, ids) -> titles.put(type, sources.titles(courseId, type, ids)));
        Map<Long, List<NodeResourceView>> views = new LinkedHashMap<>();
        for (KnowledgeNodeResource resource : all) {
            views.computeIfAbsent(resource.getNodeId(), key -> new ArrayList<>())
                    .add(new NodeResourceView(resource.getItemType(), resource.getContentId(),
                            titles.get(resource.getItemType()).get(resource.getContentId())));
        }
        return views;
    }

    GraphView view(KnowledgeGraph graph) {
        return new GraphView(graph.getId(), graph.getCourseId(), graph.getName(), graph.isPublished(),
                graph.getCreatedAt(), graph.getUpdatedAt());
    }

    private void requireAccess(long courseId, boolean management) {
        Actor actor = currentActor.require();
        if (management) {
            courseAccess.requireManagementAccess(courseId, actor);
        } else {
            courseAccess.requireLearningAccess(courseId, actor);
        }
    }

    static String itemLabel(CourseOutlineItemType type) {
        return switch (type) {
            case MATERIAL -> "文件";
            case QUESTION -> "试题";
            case PROGRAMMING_PROBLEM -> "编程题";
        };
    }

    private static void requireMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new ConflictException(message);
        }
    }

    // ---- 视图 --------------------------------------------------------------------

    public record GraphView(
            long id,
            long courseId,
            String name,
            boolean published,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record NodeResourceView(CourseOutlineItemType itemType, long contentId, String title) {
    }

    public record NodeView(
            long id,
            @Schema(nullable = true) Long parentId,
            int position,
            NodeKind kind,
            @Schema(nullable = true) KpType kpType,
            String label,
            @Schema(nullable = true) String summary,
            @Schema(nullable = true) String definition,
            @Schema(nullable = true) String explanation,
            List<String> aliases,
            @Schema(nullable = true) String code,
            @Schema(nullable = true) String language,
            @Schema(nullable = true) String sourceSectionTitle,
            @Schema(nullable = true) String quote,
            List<NodeResourceView> resources
    ) {
    }

    public record EdgeView(
            long id,
            long sourceNodeId,
            long targetNodeId,
            EdgeKind kind,
            @Schema(nullable = true) String evidence
    ) {
    }

    public record GraphSnapshot(GraphView graph, List<NodeView> nodes, List<EdgeView> edges) {
    }

    /** downloadUrl 只有文件有;试题 / 编程题由前端按 contentId 跳转 */
    public record ResourceOpenView(
            CourseOutlineItemType itemType,
            long contentId,
            @Schema(nullable = true) String downloadUrl
    ) {
    }
}
