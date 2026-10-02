package cn.utcy.teaching.knowledgegraph.domain;

import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 图谱本体规则(纯函数,每条写入路径——手工编辑与构建入库——都经过这里):
 * 父子兼容(根 / 章节 ⊇ 章节 · 知识点;知识点 ⊇ 代码示例;代码示例无子)、层级 ≤ 8、
 * 字段长度、关系两端都是知识点且前置无环、同一对只能有一种关系、规模上限。
 * 违反输入契约抛 400,与现有数据冲突抛 409。
 */
public final class GraphRules {

    public static final int MAX_DEPTH = 8;
    public static final int MAX_NODES = 5000;
    public static final int MAX_EDGES = 10000;
    public static final int MAX_RESOURCES_PER_NODE = 20;
    public static final int MAX_LABEL = 255;
    public static final int MAX_TEXT = 2000;
    public static final int MAX_ALIASES = 10;
    public static final int MAX_ALIAS = 80;
    public static final int MAX_CODE = 20000;
    public static final int MAX_LANGUAGE = 32;
    public static final int MAX_EVIDENCE = 500;
    public static final int MAX_QUOTE = 500;
    public static final int MAX_SOURCE_TITLE = 255;

    private GraphRules() {
    }

    /** parent 为 null 表示图谱根 */
    public static boolean canContain(NodeKind parent, NodeKind child) {
        if (parent == null || parent == NodeKind.UNIT) {
            return child == NodeKind.UNIT || child == NodeKind.KNOWLEDGE_POINT;
        }
        if (parent == NodeKind.KNOWLEDGE_POINT) {
            return child == NodeKind.CODE_EXAMPLE;
        }
        return false;
    }

    public static NodeContent normalizeContent(NodeContent content) {
        if (content.kind() == null) {
            throw new BadRequestException("节点类型不能为空");
        }
        String label = required(content.label(), "名称", MAX_LABEL);
        return switch (content.kind()) {
            case UNIT -> {
                requireAbsent(content.kpType() == null, "章节没有知识点小类");
                requireAbsent(isBlank(content.code()) && isBlank(content.language()), "章节没有代码");
                requireAbsent(content.aliases() == null || content.aliases().isEmpty(), "章节没有别名");
                requireAbsent(isBlank(content.definition()), "释义只属于知识点");
                requireAbsent(isBlank(content.explanation()), "说明只属于代码示例");
                requireAbsent(isBlank(content.sourceSectionTitle()) && isBlank(content.quote()),
                        "出处只属于知识点和代码示例");
                yield NodeContent.unit(label, optional(content.summary(), "摘要", MAX_TEXT));
            }
            case KNOWLEDGE_POINT -> {
                if (content.kpType() == null) {
                    throw new BadRequestException("知识点必须选择小类");
                }
                requireAbsent(isBlank(content.code()) && isBlank(content.language()), "知识点没有代码");
                requireAbsent(isBlank(content.summary()), "摘要只属于章节");
                requireAbsent(isBlank(content.explanation()), "说明只属于代码示例");
                yield NodeContent.knowledgePoint(label, content.kpType(),
                        optional(content.definition(), "释义", MAX_TEXT),
                        normalizeAliases(content.aliases(), label),
                        optional(content.sourceSectionTitle(), "出处小节", MAX_SOURCE_TITLE),
                        optional(content.quote(), "原文引文", MAX_QUOTE));
            }
            case CODE_EXAMPLE -> {
                requireAbsent(content.kpType() == null, "代码示例没有知识点小类");
                requireAbsent(content.aliases() == null || content.aliases().isEmpty(), "代码示例没有别名");
                requireAbsent(isBlank(content.summary()), "摘要只属于章节");
                requireAbsent(isBlank(content.definition()), "释义只属于知识点");
                String code = content.code() == null ? "" : content.code().strip();
                if (code.isEmpty()) {
                    throw new BadRequestException("代码不能为空");
                }
                if (code.length() > MAX_CODE) {
                    throw new BadRequestException("代码不能超过 " + MAX_CODE + " 个字符");
                }
                String language = required(content.language(), "语言", MAX_LANGUAGE);
                yield NodeContent.codeExample(label, optional(content.explanation(), "说明", MAX_TEXT), code, language,
                        optional(content.sourceSectionTitle(), "出处小节", MAX_SOURCE_TITLE),
                        optional(content.quote(), "原文引文", MAX_QUOTE));
            }
        };
    }

    private static List<String> normalizeAliases(List<String> aliases, String label) {
        if (aliases == null || aliases.isEmpty()) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String alias : aliases) {
            String value = alias == null ? "" : alias.strip();
            if (value.isEmpty()) {
                throw new BadRequestException("别名不能为空");
            }
            if (value.length() > MAX_ALIAS) {
                throw new BadRequestException("别名不能超过 " + MAX_ALIAS + " 个字符");
            }
            if (value.equals(label)) {
                throw new BadRequestException("别名不能与名称相同");
            }
            unique.add(value);
        }
        if (unique.size() > MAX_ALIASES) {
            throw new BadRequestException("别名最多 " + MAX_ALIASES + " 个");
        }
        return List.copyOf(unique);
    }

    public static void requireParent(GraphModel model, KnowledgeNode parent, NodeKind childKind) {
        NodeKind parentKind = parent == null ? null : parent.getKind();
        if (!canContain(parentKind, childKind)) {
            throw new BadRequestException(containerLabel(parentKind) + "下不能添加" + childKind.displayName());
        }
        int parentDepth = parent == null ? 0 : model.depth(parent.getId());
        if (parentDepth + 1 > MAX_DEPTH) {
            throw new BadRequestException("层级不能超过 " + MAX_DEPTH + " 层");
        }
    }

    public static void requireEdge(GraphModel model, KnowledgeNode source, KnowledgeNode target, EdgeKind kind) {
        if (source.getKind() != NodeKind.KNOWLEDGE_POINT || target.getKind() != NodeKind.KNOWLEDGE_POINT) {
            throw new BadRequestException("只有知识点之间可以建立关系");
        }
        if (source.getId().equals(target.getId())) {
            throw new BadRequestException("知识点不能与自己建立关系");
        }
        for (KnowledgeEdge existing : model.edgesBetween(source.getId(), target.getId())) {
            if (existing.getKind() == kind && (kind == EdgeKind.RELATED
                    || existing.getSourceNodeId() == source.getId())) {
                throw new ConflictException("这条关系已存在");
            }
            throw new ConflictException("「" + source.getLabel() + "」与「" + target.getLabel() + "」已有"
                    + existing.getKind().displayName() + "关系，请先删除");
        }
        if (kind == EdgeKind.PREREQUISITE) {
            List<Long> back = model.prerequisitePath(target.getId(), source.getId());
            if (back != null) {
                List<String> labels = new ArrayList<>();
                labels.add(source.getLabel());
                for (long id : back) {
                    labels.add(model.node(id).getLabel());
                }
                throw new ConflictException("会形成循环前置：" + String.join(" → ", labels));
            }
        }
    }

    /** 相关关系无向:按 id 升序存储,便于唯一键去重 */
    public static long[] normalizeRelated(long a, long b) {
        return a <= b ? new long[]{a, b} : new long[]{b, a};
    }

    public static void requireCapacity(int nodeCount, int edgeCount) {
        if (nodeCount > MAX_NODES) {
            throw new ConflictException("单个图谱最多 " + MAX_NODES + " 个节点");
        }
        if (edgeCount > MAX_EDGES) {
            throw new ConflictException("单个图谱最多 " + MAX_EDGES + " 条关系");
        }
    }

    public static void requireAttachable(KnowledgeNode node, int currentResourceCount) {
        if (node.getKind() == NodeKind.CODE_EXAMPLE) {
            throw new BadRequestException("代码示例不能挂载资源");
        }
        if (currentResourceCount >= MAX_RESOURCES_PER_NODE) {
            throw new ConflictException("单个节点最多挂载 " + MAX_RESOURCES_PER_NODE + " 个资源");
        }
    }

    /**
     * 整图校验(构建入库后):每个父节点的子节点位置从 1 连续、父子兼容、层级、关系合法、规模。
     * 入库文档由 GraphAssembler 生成,违反即程序错误。
     */
    public static void validateTree(GraphModel model) {
        requireCapacity(model.nodeCount(), model.edgeCount());
        List<Long> scopes = new ArrayList<>();
        scopes.add(GraphModel.ROOT);
        model.nodes().forEach(node -> scopes.add(node.getId()));
        for (long scope : scopes) {
            KnowledgeNode parent = scope == GraphModel.ROOT ? null : model.node(scope);
            List<KnowledgeNode> children = model.children(scope == GraphModel.ROOT ? null : scope);
            for (int i = 0; i < children.size(); i++) {
                KnowledgeNode child = children.get(i);
                if (child.getPosition() != i + 1) {
                    throw new IllegalStateException("节点位置不连续：" + child.getLabel());
                }
                if (!canContain(parent == null ? null : parent.getKind(), child.getKind())) {
                    throw new IllegalStateException("父子类型不兼容：" + child.getLabel());
                }
            }
        }
        for (KnowledgeNode node : model.nodes()) {
            if (node.getParentId() != null && model.node(node.getParentId()) == null) {
                throw new IllegalStateException("父节点缺失：" + node.getLabel());
            }
            if (model.depth(node.getId()) > MAX_DEPTH) {
                throw new IllegalStateException("层级超过上限：" + node.getLabel());
            }
        }
        for (KnowledgeEdge edge : model.edges()) {
            KnowledgeNode source = model.node(edge.getSourceNodeId());
            KnowledgeNode target = model.node(edge.getTargetNodeId());
            if (source == null || target == null || source.getKind() != NodeKind.KNOWLEDGE_POINT
                    || target.getKind() != NodeKind.KNOWLEDGE_POINT || source.getId().equals(target.getId())) {
                throw new IllegalStateException("关系端点不合法：" + edge.getSourceNodeId() + " → " + edge.getTargetNodeId());
            }
            if (edge.getKind() == EdgeKind.RELATED && edge.getSourceNodeId() > edge.getTargetNodeId()) {
                throw new IllegalStateException("相关关系未规范化：" + edge.getSourceNodeId() + " → " + edge.getTargetNodeId());
            }
        }
        for (KnowledgeEdge edge : model.edges()) {
            if (edge.getKind() == EdgeKind.PREREQUISITE
                    && model.prerequisitePath(edge.getTargetNodeId(), edge.getSourceNodeId()) != null) {
                throw new IllegalStateException("前置关系成环：" + edge.getSourceNodeId() + " → " + edge.getTargetNodeId());
            }
        }
    }

    public static String truncate(String text, int max) {
        return text == null ? null : Text.truncate(text.strip(), max);
    }

    private static String containerLabel(NodeKind kind) {
        return kind == null ? "图谱根" : kind.displayName();
    }

    private static String required(String value, String field, int max) {
        String stripped = value == null ? "" : value.strip();
        if (stripped.isEmpty()) {
            throw new BadRequestException(field + "不能为空");
        }
        if (stripped.length() > max) {
            throw new BadRequestException(field + "不能超过 " + max + " 个字符");
        }
        return stripped;
    }

    private static String optional(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        if (stripped.length() > max) {
            throw new BadRequestException(field + "不能超过 " + max + " 个字符");
        }
        return stripped;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void requireAbsent(boolean absent, String message) {
        if (!absent) {
            throw new BadRequestException(message);
        }
    }
}
