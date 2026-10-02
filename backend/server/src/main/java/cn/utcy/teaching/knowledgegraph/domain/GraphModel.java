package cn.utcy.teaching.knowledgegraph.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一张图谱的内存模型(只读):节点树、语义关系与挂载,供规则校验与视图组装。
 * 顶层节点的父键统一用 0(与表里的 parent_scope 生成列一致)。
 */
public final class GraphModel {

    public static final long ROOT = 0L;

    private final Map<Long, KnowledgeNode> nodes = new LinkedHashMap<>();
    private final Map<Long, List<KnowledgeNode>> childrenByParent = new HashMap<>();
    private final List<KnowledgeEdge> edges;
    private final Map<Long, List<KnowledgeNodeResource>> resourcesByNode = new HashMap<>();

    public GraphModel(List<KnowledgeNode> nodeRows, List<KnowledgeEdge> edgeRows,
                      List<KnowledgeNodeResource> resourceRows) {
        for (KnowledgeNode node : nodeRows) {
            nodes.put(node.getId(), node);
            childrenByParent.computeIfAbsent(scope(node.getParentId()), key -> new ArrayList<>()).add(node);
        }
        childrenByParent.values().forEach(children ->
                children.sort(Comparator.comparingInt(KnowledgeNode::getPosition)));
        this.edges = List.copyOf(edgeRows);
        for (KnowledgeNodeResource resource : resourceRows) {
            resourcesByNode.computeIfAbsent(resource.getNodeId(), key -> new ArrayList<>()).add(resource);
        }
    }

    public static long scope(Long parentId) {
        return parentId == null ? ROOT : parentId;
    }

    public Collection<KnowledgeNode> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public List<KnowledgeEdge> edges() {
        return edges;
    }

    public int nodeCount() {
        return nodes.size();
    }

    public int edgeCount() {
        return edges.size();
    }

    public KnowledgeNode node(long id) {
        return nodes.get(id);
    }

    public List<KnowledgeNode> children(Long parentId) {
        return childrenByParent.getOrDefault(scope(parentId), List.of());
    }

    /** 顶层节点深度为 1 */
    public int depth(long nodeId) {
        int depth = 0;
        KnowledgeNode current = nodes.get(nodeId);
        while (current != null) {
            depth++;
            current = current.getParentId() == null ? null : nodes.get(current.getParentId());
        }
        return depth;
    }

    public List<Long> subtree(long nodeId) {
        List<Long> ids = new ArrayList<>();
        Deque<Long> stack = new ArrayDeque<>();
        stack.push(nodeId);
        while (!stack.isEmpty()) {
            long current = stack.pop();
            ids.add(current);
            List<KnowledgeNode> children = children(current);
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i).getId());
            }
        }
        return ids;
    }

    public List<String> unitPath(long nodeId) {
        List<String> path = new ArrayList<>();
        KnowledgeNode current = nodes.get(nodeId);
        while (current != null && current.getParentId() != null) {
            current = nodes.get(current.getParentId());
            if (current != null) {
                path.add(0, current.getLabel());
            }
        }
        return path;
    }

    public KnowledgeEdge edge(long sourceId, long targetId, EdgeKind kind) {
        for (KnowledgeEdge edge : edges) {
            if (edge.getKind() == kind && edge.getSourceNodeId() == sourceId && edge.getTargetNodeId() == targetId) {
                return edge;
            }
        }
        return null;
    }

    public KnowledgeEdge edgeById(long edgeId) {
        for (KnowledgeEdge edge : edges) {
            if (edge.getId() == edgeId) {
                return edge;
            }
        }
        return null;
    }

    public List<KnowledgeEdge> edgesBetween(long a, long b) {
        List<KnowledgeEdge> found = new ArrayList<>();
        for (KnowledgeEdge edge : edges) {
            if (edge.touches(a) && edge.touches(b)) {
                found.add(edge);
            }
        }
        return found;
    }

    public List<KnowledgeEdge> edgesOf(long nodeId) {
        List<KnowledgeEdge> found = new ArrayList<>();
        for (KnowledgeEdge edge : edges) {
            if (edge.touches(nodeId)) {
                found.add(edge);
            }
        }
        return found;
    }

    /**
     * 沿前置边(source → target)从 from 走到 to 的一条路径(含两端);不可达返回 null。
     * 用于成环判定:新边 A → B 会成环,当且仅当已有 B → … → A。
     */
    public List<Long> prerequisitePath(long from, long to) {
        Map<Long, List<Long>> next = new HashMap<>();
        for (KnowledgeEdge edge : edges) {
            if (edge.getKind() == EdgeKind.PREREQUISITE) {
                next.computeIfAbsent(edge.getSourceNodeId(), key -> new ArrayList<>()).add(edge.getTargetNodeId());
            }
        }
        Map<Long, Long> cameFrom = new HashMap<>();
        Set<Long> seen = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(from);
        seen.add(from);
        while (!queue.isEmpty()) {
            long current = queue.poll();
            if (current == to) {
                List<Long> path = new ArrayList<>();
                Long step = to;
                while (step != null) {
                    path.add(0, step);
                    step = cameFrom.get(step);
                }
                return path;
            }
            for (long candidate : next.getOrDefault(current, List.of())) {
                if (seen.add(candidate)) {
                    cameFrom.put(candidate, current);
                    queue.add(candidate);
                }
            }
        }
        return null;
    }

    public List<KnowledgeNodeResource> resources(long nodeId) {
        return resourcesByNode.getOrDefault(nodeId, List.of());
    }
}
