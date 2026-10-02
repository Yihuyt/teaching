package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.GraphSnapshot;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.GraphView;
import cn.utcy.teaching.knowledgegraph.domain.BuildPreview;
import cn.utcy.teaching.knowledgegraph.domain.EdgeKind;
import cn.utcy.teaching.knowledgegraph.domain.GraphModel;
import cn.utcy.teaching.knowledgegraph.domain.GraphRules;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeEdge;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeGraph;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeNode;
import cn.utcy.teaching.knowledgegraph.domain.KnowledgeNodeResource;
import cn.utcy.teaching.knowledgegraph.domain.NodeContent;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeEdgeMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeNodeMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeNodeResourceMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.NodeResourceSources;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱内容的全部写操作,即时落库、逐操作校验:
 * 每个操作先锁图谱行(串行化同一图谱的写入),读整图模型,经 {@link GraphRules} 校验,写入,
 * 再由 {@link KnowledgeGraphChangeMarker} touch 图谱并发事件,最后返回新快照。
 * 基于过期视图的操作由校验兜住:节点已不存在 → 404「节点已被删除,请刷新」,成环拒绝。
 */
@Service
public class KnowledgeGraphEditor {

    private final KnowledgeGraphMapper graphs;
    private final KnowledgeNodeMapper nodes;
    private final KnowledgeEdgeMapper edges;
    private final KnowledgeNodeResourceMapper resources;
    private final NodeResourceSources sources;
    private final KnowledgeGraphService service;
    private final KnowledgeGraphChangeMarker changes;
    private final AliasesCodec aliases;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final Clock clock;

    public KnowledgeGraphEditor(KnowledgeGraphMapper graphs, KnowledgeNodeMapper nodes, KnowledgeEdgeMapper edges,
                                KnowledgeNodeResourceMapper resources, NodeResourceSources sources,
                                KnowledgeGraphService service, KnowledgeGraphChangeMarker changes,
                                AliasesCodec aliases, CourseAccess courseAccess, CurrentActor currentActor,
                                Clock clock) {
        this.graphs = graphs;
        this.nodes = nodes;
        this.edges = edges;
        this.resources = resources;
        this.sources = sources;
        this.service = service;
        this.changes = changes;
        this.aliases = aliases;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    public record NodeCreated(long createdNodeId, GraphSnapshot snapshot) {
    }

    @Transactional
    public NodeCreated createNode(long courseId, long graphId, Long parentId, NodeContent content) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraph graph = service.requireGraphForUpdate(courseId, graphId);
        GraphModel model = service.loadModel(graphId);
        KnowledgeNode parent = parentId == null ? null : requireNode(model, parentId);
        NodeContent normalized = GraphRules.normalizeContent(content);
        GraphRules.requireParent(model, parent, normalized.kind());
        GraphRules.requireCapacity(model.nodeCount() + 1, model.edgeCount());
        KnowledgeNode node = newNode(graphId, parentId, model.children(parentId).size() + 1, normalized);
        requireMutation(nodes.insert(node), "节点创建未生效");
        changes.changed(graph);
        return new NodeCreated(node.getId(), service.snapshot(graph, service.loadModel(graphId)));
    }

    @Transactional
    public GraphSnapshot updateNode(long courseId, long graphId, long nodeId, NodeContent content) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraph graph = service.requireGraphForUpdate(courseId, graphId);
        GraphModel model = service.loadModel(graphId);
        KnowledgeNode node = requireNode(model, nodeId);
        NodeContent normalized = GraphRules.normalizeContent(new NodeContent(node.getKind(), content.label(),
                content.kpType(), content.summary(), content.definition(), content.explanation(),
                content.aliases(), content.code(), content.language(),
                content.sourceSectionTitle(), content.quote()));
        node.update(normalized.kpType(), normalized.label(), normalized.summary(), normalized.definition(),
                normalized.explanation(), aliases.write(normalized.aliases()), normalized.code(),
                normalized.language(), normalized.sourceSectionTitle(), normalized.quote(), clock.instant());
        requireMutation(nodes.updateById(node), "节点状态已变化，保存未生效");
        changes.changed(graph);
        return service.snapshot(graph, service.loadModel(graphId));
    }

    @Transactional
    public GraphSnapshot deleteNode(long courseId, long graphId, long nodeId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraph graph = service.requireGraphForUpdate(courseId, graphId);
        GraphModel model = service.loadModel(graphId);
        KnowledgeNode node = requireNode(model, nodeId);
        List<Long> subtree = model.subtree(nodeId);
        resources.delete(new LambdaQueryWrapper<KnowledgeNodeResource>()
                .eq(KnowledgeNodeResource::getGraphId, graphId)
                .in(KnowledgeNodeResource::getNodeId, subtree));
        edges.delete(new LambdaQueryWrapper<KnowledgeEdge>()
                .eq(KnowledgeEdge::getGraphId, graphId)
                .and(wrapper -> wrapper.in(KnowledgeEdge::getSourceNodeId, subtree)
                        .or()
                        .in(KnowledgeEdge::getTargetNodeId, subtree)));
        nodes.delete(new LambdaQueryWrapper<KnowledgeNode>()
                .eq(KnowledgeNode::getGraphId, graphId)
                .in(KnowledgeNode::getId, subtree));
        nodes.closeGap(graphId, GraphModel.scope(node.getParentId()), node.getPosition(), nodeId);
        changes.changed(graph);
        return service.snapshot(graph, service.loadModel(graphId));
    }

    @Transactional
    public GraphSnapshot createEdge(long courseId, long graphId, long sourceNodeId, long targetNodeId, EdgeKind kind) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraph graph = service.requireGraphForUpdate(courseId, graphId);
        GraphModel model = service.loadModel(graphId);
        KnowledgeNode source = requireNode(model, sourceNodeId);
        KnowledgeNode target = requireNode(model, targetNodeId);
        GraphRules.requireEdge(model, source, target, kind);
        GraphRules.requireCapacity(model.nodeCount(), model.edgeCount() + 1);
        long[] pair = kind == EdgeKind.RELATED
                ? GraphRules.normalizeRelated(sourceNodeId, targetNodeId)
                : new long[]{sourceNodeId, targetNodeId};
        try {
            requireMutation(edges.insert(new KnowledgeEdge(graphId, pair[0], pair[1], kind, null, clock.instant())),
                    "关系创建未生效");
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("这条关系已存在");
        }
        changes.changed(graph);
        return service.snapshot(graph, service.loadModel(graphId));
    }

    @Transactional
    public GraphSnapshot deleteEdge(long courseId, long graphId, long edgeId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraph graph = service.requireGraphForUpdate(courseId, graphId);
        GraphModel model = service.loadModel(graphId);
        if (model.edgeById(edgeId) == null) {
            throw new NotFoundException("关系已被删除，请刷新");
        }
        requireMutation(edges.deleteById(edgeId), "关系删除未生效");
        changes.changed(graph);
        return service.snapshot(graph, service.loadModel(graphId));
    }

    /** 锁序固定为 内容行 → 图谱行(与内容删除守卫一致),避免与删除内容互相等待 */
    @Transactional
    public GraphSnapshot attachResource(long courseId, long graphId, long nodeId, CourseOutlineItemType itemType,
                                        long contentId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        sources.requireLinkable(courseId, itemType, contentId);
        KnowledgeGraph graph = service.requireGraphForUpdate(courseId, graphId);
        GraphModel model = service.loadModel(graphId);
        KnowledgeNode node = requireNode(model, nodeId);
        List<KnowledgeNodeResource> attached = model.resources(nodeId);
        if (attached.stream().anyMatch(r -> r.getItemType() == itemType && r.getContentId() == contentId)) {
            throw new ConflictException("该资源已挂在此节点上");
        }
        GraphRules.requireAttachable(node, attached.size());
        requireMutation(resources.insert(new KnowledgeNodeResource(graphId, nodeId, itemType, contentId,
                clock.instant())), "挂载未生效");
        changes.changed(graph);
        return service.snapshot(graph, service.loadModel(graphId));
    }

    @Transactional
    public GraphSnapshot detachResource(long courseId, long graphId, long nodeId, CourseOutlineItemType itemType,
                                        long contentId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraph graph = service.requireGraphForUpdate(courseId, graphId);
        int removed = resources.delete(new LambdaQueryWrapper<KnowledgeNodeResource>()
                .eq(KnowledgeNodeResource::getGraphId, graphId)
                .eq(KnowledgeNodeResource::getNodeId, nodeId)
                .eq(KnowledgeNodeResource::getItemType, itemType)
                .eq(KnowledgeNodeResource::getContentId, contentId));
        if (removed == 0) {
            throw new NotFoundException("该节点未挂载此资源");
        }
        changes.changed(graph);
        return service.snapshot(graph, service.loadModel(graphId));
    }

    /**
     * 构建入库:新建图谱 + 父先子后写入节点(临时键 → id)+ 关系,整图校验后发事件。
     * 调用方(构建服务)已完成鉴权与状态校验;入库文档由 GraphAssembler 生成,违反规则即程序错误。
     */
    @Transactional
    public GraphView createFromPreview(long courseId, String name, BuildPreview preview) {
        KnowledgeGraph graph = KnowledgeGraph.create(courseId, name.strip(), clock.instant());
        try {
            requireMutation(graphs.insert(graph), "知识图谱创建未生效");
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("同一课程内的知识图谱名称不能重复");
        }
        Map<String, Long> ids = new HashMap<>();
        for (BuildPreview.PreviewNode preview1 : preview.nodes()) {
            Long parentId = null;
            if (preview1.parentKey() != null) {
                parentId = ids.get(preview1.parentKey());
                if (parentId == null) {
                    throw new IllegalStateException("入库文档的父节点未先于子节点：" + preview1.key());
                }
            }
            KnowledgeNode node = newNode(graph.getId(), parentId, preview1.position(),
                    GraphRules.normalizeContent(preview1.content()));
            requireMutation(nodes.insert(node), "节点写入未生效");
            ids.put(preview1.key(), node.getId());
        }
        for (BuildPreview.PreviewEdge edge : preview.edges()) {
            Long source = ids.get(edge.sourceKey());
            Long target = ids.get(edge.targetKey());
            if (source == null || target == null) {
                throw new IllegalStateException("入库文档的关系端点不存在：" + edge.sourceKey() + " → " + edge.targetKey());
            }
            long[] pair = edge.kind() == EdgeKind.RELATED
                    ? GraphRules.normalizeRelated(source, target) : new long[]{source, target};
            try {
                requireMutation(edges.insert(new KnowledgeEdge(graph.getId(), pair[0], pair[1], edge.kind(),
                        GraphRules.truncate(edge.evidence(), GraphRules.MAX_EVIDENCE), clock.instant())),
                        "关系写入未生效");
            } catch (DuplicateKeyException exception) {
                throw new IllegalStateException("入库文档存在重复关系：" + edge.sourceKey() + " → " + edge.targetKey());
            }
        }
        GraphRules.validateTree(service.loadModel(graph.getId()));
        changes.changed(graph);
        return service.view(graph);
    }

    private KnowledgeNode newNode(long graphId, Long parentId, int position, NodeContent content) {
        return new KnowledgeNode(graphId, parentId, position, content.kind(), content.kpType(), content.label(),
                content.summary(), content.definition(), content.explanation(), aliases.write(content.aliases()),
                content.code(), content.language(), content.sourceSectionTitle(), content.quote(), clock.instant());
    }

    private static KnowledgeNode requireNode(GraphModel model, long nodeId) {
        KnowledgeNode node = model.node(nodeId);
        if (node == null) {
            throw new NotFoundException("节点已被删除，请刷新");
        }
        return node;
    }

    private static void requireMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new ConflictException(message);
        }
    }
}
