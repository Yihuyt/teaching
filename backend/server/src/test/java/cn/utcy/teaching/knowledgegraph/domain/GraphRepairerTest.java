package cn.utcy.teaching.knowledgegraph.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphRepairerTest {

    @Test
    @DisplayName("前置依赖环被确定性拆除:删 evidence 最短边,相关关系保留")
    void removesPrerequisiteCycles() {
        List<GraphRepairer.Relation> relations = List.of(
                new GraphRepairer.Relation("甲", "乙", EdgeKind.PREREQUISITE, "很长很长的证据文本"),
                new GraphRepairer.Relation("乙", "丙", EdgeKind.PREREQUISITE, "中等长度证据"),
                new GraphRepairer.Relation("丙", "甲", EdgeKind.PREREQUISITE, "短"),
                new GraphRepairer.Relation("甲", "丙", EdgeKind.RELATED, "无关"));

        GraphRepairer.RemovalResult result = GraphRepairer.removePrerequisiteCycles(relations);

        assertThat(result.removedEdges()).containsExactly("丙 → 甲");
        assertThat(result.relations()).hasSize(3);
        assertThat(GraphRepairer.removePrerequisiteCycles(result.relations()).removedEdges()).isEmpty();
    }

    @Test
    @DisplayName("两点互为前置也是环,只留证据更强的一条")
    void breaksTwoNodeCycle() {
        List<GraphRepairer.Relation> relations = List.of(
                new GraphRepairer.Relation("甲", "乙", EdgeKind.PREREQUISITE, "证据充分"),
                new GraphRepairer.Relation("乙", "甲", EdgeKind.PREREQUISITE, "弱"));

        GraphRepairer.RemovalResult result = GraphRepairer.removePrerequisiteCycles(relations);

        assertThat(result.removedEdges()).containsExactly("乙 → 甲");
        assertThat(result.relations()).extracting(GraphRepairer.Relation::sourceName).containsExactly("甲");
    }
}
