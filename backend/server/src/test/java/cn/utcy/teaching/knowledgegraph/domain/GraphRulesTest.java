package cn.utcy.teaching.knowledgegraph.domain;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphRulesTest {

    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

    private static KnowledgeNode node(long id, Long parentId, int position, NodeKind kind, String label) {
        KnowledgeNode node = new KnowledgeNode(1L, parentId, position, kind,
                kind == NodeKind.KNOWLEDGE_POINT ? KpType.CONCEPT : null, label, null, null, null, null,
                kind == NodeKind.CODE_EXAMPLE ? "x = 1" : null, kind == NodeKind.CODE_EXAMPLE ? "python" : null,
                null, null, NOW);
        setId(node, id);
        return node;
    }

    private static KnowledgeEdge edge(long id, long source, long target, EdgeKind kind) {
        KnowledgeEdge edge = new KnowledgeEdge(1L, source, target, kind, null, NOW);
        try {
            var field = KnowledgeEdge.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(edge, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        return edge;
    }

    private static void setId(KnowledgeNode node, long id) {
        try {
            var field = KnowledgeNode.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(node, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    @DisplayName("父子矩阵:根与章节含章节 / 知识点,知识点只含代码示例,代码示例无子")
    void containmentMatrix() {
        assertThat(GraphRules.canContain(null, NodeKind.UNIT)).isTrue();
        assertThat(GraphRules.canContain(null, NodeKind.KNOWLEDGE_POINT)).isTrue();
        assertThat(GraphRules.canContain(null, NodeKind.CODE_EXAMPLE)).isFalse();
        assertThat(GraphRules.canContain(NodeKind.UNIT, NodeKind.CODE_EXAMPLE)).isFalse();
        assertThat(GraphRules.canContain(NodeKind.KNOWLEDGE_POINT, NodeKind.CODE_EXAMPLE)).isTrue();
        assertThat(GraphRules.canContain(NodeKind.KNOWLEDGE_POINT, NodeKind.UNIT)).isFalse();
        assertThat(GraphRules.canContain(NodeKind.CODE_EXAMPLE, NodeKind.CODE_EXAMPLE)).isFalse();
    }

    @Test
    @DisplayName("内容规范化:知识点必有小类、代码示例必有代码与语言、别名去重且不等于名称")
    void contentNormalization() {
        assertThatThrownBy(() -> GraphRules.normalizeContent(
                new NodeContent(NodeKind.KNOWLEDGE_POINT, "变量", null, null, null, null, List.of(), null, null, null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("小类");
        assertThatThrownBy(() -> GraphRules.normalizeContent(
                new NodeContent(NodeKind.CODE_EXAMPLE, "示例", null, null, null, null, List.of(), "  ", "python", null, null)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("代码不能为空");
        assertThatThrownBy(() -> GraphRules.normalizeContent(
                new NodeContent(NodeKind.UNIT, "第一章", KpType.CONCEPT, null, null, null, List.of(), null, null, null, null)))
                .isInstanceOf(BadRequestException.class);
        // 文字栏按类型各归其名:章节只有摘要,知识点只有释义
        assertThatThrownBy(() -> GraphRules.normalizeContent(
                new NodeContent(NodeKind.UNIT, "第一章", null, null, "释义", null, List.of(), null, null, null, null)))
                .hasMessageContaining("释义只属于知识点");
        assertThatThrownBy(() -> GraphRules.normalizeContent(
                new NodeContent(NodeKind.KNOWLEDGE_POINT, "变量", KpType.CONCEPT, "摘要", null, null, List.of(), null, null, null, null)))
                .hasMessageContaining("摘要只属于章节");
        NodeContent kp = GraphRules.normalizeContent(new NodeContent(NodeKind.KNOWLEDGE_POINT, " 变量 ",
                KpType.CONCEPT, null, " 释义 ", null, List.of("var", "var", "变量名"), null, null, null, null));
        assertThat(kp.label()).isEqualTo("变量");
        assertThat(kp.definition()).isEqualTo("释义");
        assertThat(kp.aliases()).containsExactly("var", "变量名");
        assertThatThrownBy(() -> GraphRules.normalizeContent(new NodeContent(NodeKind.KNOWLEDGE_POINT, "变量",
                KpType.CONCEPT, null, null, null, List.of("变量"), null, null, null, null)))
                .hasMessageContaining("别名不能与名称相同");
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            tooMany.add("别名" + i);
        }
        assertThatThrownBy(() -> GraphRules.normalizeContent(new NodeContent(NodeKind.KNOWLEDGE_POINT, "变量",
                KpType.CONCEPT, null, null, null, tooMany, null, null, null, null)))
                .hasMessageContaining("最多 10 个");
        // 出处:知识点/代码示例可选,章节不允许;超长 400
        assertThat(GraphRules.normalizeContent(new NodeContent(NodeKind.KNOWLEDGE_POINT, "变量",
                KpType.CONCEPT, null, null, null, List.of(), null, null, " 2.1 变量 ", " 原文句 ")).quote())
                .isEqualTo("原文句");
        assertThatThrownBy(() -> GraphRules.normalizeContent(new NodeContent(NodeKind.UNIT, "第一章",
                null, null, null, null, List.of(), null, null, null, "引文")))
                .hasMessageContaining("出处只属于知识点和代码示例");
        assertThatThrownBy(() -> GraphRules.normalizeContent(new NodeContent(NodeKind.KNOWLEDGE_POINT, "变量",
                KpType.CONCEPT, null, null, null, List.of(), null, null, null, "超".repeat(501))))
                .hasMessageContaining("原文引文不能超过 500 个字符");
    }

    @Test
    @DisplayName("层级上限 8:第 8 层下不能再加;移动时按子树高度算")
    void depthLimit() {
        List<KnowledgeNode> nodes = new ArrayList<>();
        Long parent = null;
        for (int i = 1; i <= 8; i++) {
            nodes.add(node(i, parent, 1, NodeKind.UNIT, "第 " + i + " 层"));
            parent = (long) i;
        }
        GraphModel model = new GraphModel(nodes, List.of(), List.of());

        assertThat(model.depth(8)).isEqualTo(8);
        assertThatThrownBy(() -> GraphRules.requireParent(model, model.node(8), NodeKind.KNOWLEDGE_POINT))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("层级");
        GraphRules.requireParent(model, model.node(7), NodeKind.KNOWLEDGE_POINT);
    }

    @Test
    @DisplayName("前置成环被拒并给出环路文案;相关关系规范化;同一对只能一种关系")
    void edges() {
        KnowledgeNode a = node(1, null, 1, NodeKind.KNOWLEDGE_POINT, "A");
        KnowledgeNode b = node(2, null, 2, NodeKind.KNOWLEDGE_POINT, "B");
        KnowledgeNode c = node(3, null, 3, NodeKind.KNOWLEDGE_POINT, "C");
        KnowledgeNode unit = node(4, null, 4, NodeKind.UNIT, "章");
        GraphModel model = new GraphModel(List.of(a, b, c, unit), List.of(
                edge(1, 1, 2, EdgeKind.PREREQUISITE), edge(2, 2, 3, EdgeKind.PREREQUISITE)), List.of());

        assertThatThrownBy(() -> GraphRules.requireEdge(model, c, a, EdgeKind.PREREQUISITE))
                .isInstanceOf(ConflictException.class)
                .hasMessage("会形成循环前置：C → A → B → C");
        assertThatThrownBy(() -> GraphRules.requireEdge(model, a, b, EdgeKind.PREREQUISITE))
                .hasMessage("这条关系已存在");
        assertThatThrownBy(() -> GraphRules.requireEdge(model, b, a, EdgeKind.RELATED))
                .hasMessageContaining("已有前置关系");
        assertThatThrownBy(() -> GraphRules.requireEdge(model, a, unit, EdgeKind.RELATED))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> GraphRules.requireEdge(model, a, a, EdgeKind.RELATED))
                .isInstanceOf(BadRequestException.class);
        GraphRules.requireEdge(model, c, a, EdgeKind.RELATED);
        assertThat(GraphRules.normalizeRelated(9, 4)).containsExactly(4, 9);
    }

    @Test
    @DisplayName("整图校验:位置不连续或父子不兼容即程序错误")
    void validateTree() {
        KnowledgeNode unit = node(1, null, 1, NodeKind.UNIT, "章");
        KnowledgeNode kp = node(2, 1L, 1, NodeKind.KNOWLEDGE_POINT, "点");
        KnowledgeNode code = node(3, 2L, 1, NodeKind.CODE_EXAMPLE, "例");
        GraphRules.validateTree(new GraphModel(List.of(unit, kp, code), List.of(), List.of()));

        KnowledgeNode gap = node(4, 1L, 3, NodeKind.KNOWLEDGE_POINT, "断号");
        assertThatThrownBy(() -> GraphRules.validateTree(new GraphModel(List.of(unit, kp, code, gap), List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("位置不连续");
        KnowledgeNode misplaced = node(5, 1L, 2, NodeKind.CODE_EXAMPLE, "章下的代码");
        assertThatThrownBy(() -> GraphRules.validateTree(new GraphModel(List.of(unit, kp, code, misplaced), List.of(), List.of())))
                .hasMessageContaining("父子类型不兼容");
    }
}
