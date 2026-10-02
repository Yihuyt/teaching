package cn.utcy.teaching.knowledgegraph.domain;

import cn.utcy.teaching.knowledgegraph.domain.BuildPreview.PreviewEdge;
import cn.utcy.teaching.knowledgegraph.domain.BuildPreview.PreviewNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GraphAssemblerTest {

    private static final List<TocEntry> ENTRIES = List.of(
            new TocEntry("第1章", "初识", 1, 1, 4),
            new TocEntry("1.1", "甲节", 2, 1, 3),
            new TocEntry("1.2", "乙节", 2, 3, 4));

    private static EntityMerger.MergedKp kp(String name, String... aliases) {
        return new EntityMerger.MergedKp(name, "概念", "释义", List.of(aliases),
                List.of("原文引文"), List.of(0));
    }

    @Test
    @DisplayName("章节树按层级栈装配、兄弟从 1 连续编号;知识点挂首现节并带出处;无知识点的抽取叶产生警告")
    void assemblesTree() {
        BuildPreview preview = GraphAssembler.assemble(ENTRIES, Map.of(0, "章摘要", 1, "甲节摘要"),
                List.of(kp("变量", "变量名", "变量")), Map.of(0, 1), Set.of(1, 2), List.of(),
                List.of(new GraphAssembler.CodeExample("打印示例", "python", "print(1)", "打印",
                        List.of("print(1)"), List.of("变量"))));

        assertThat(preview.nodes()).extracting(PreviewNode::key, PreviewNode::parentKey, PreviewNode::position,
                        PreviewNode::kind)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("u0", null, 1, NodeKind.UNIT),
                        org.assertj.core.groups.Tuple.tuple("u1", "u0", 1, NodeKind.UNIT),
                        org.assertj.core.groups.Tuple.tuple("u2", "u0", 2, NodeKind.UNIT),
                        org.assertj.core.groups.Tuple.tuple("k0", "u1", 1, NodeKind.KNOWLEDGE_POINT),
                        org.assertj.core.groups.Tuple.tuple("c0", "k0", 1, NodeKind.CODE_EXAMPLE));
        PreviewNode kp = preview.nodes().get(3);
        assertThat(kp.sourceSectionTitle()).isEqualTo("1.1 甲节");
        assertThat(kp.quote()).isEqualTo("原文引文");
        assertThat(kp.definition()).isEqualTo("释义");
        assertThat(kp.aliases()).containsExactly("变量名");
        assertThat(kp.kpType()).isEqualTo(KpType.CONCEPT);
        assertThat(preview.nodes().get(0).summary()).isEqualTo("章摘要");
        assertThat(preview.nodes().get(4).code()).isEqualTo("print(1)");
        assertThat(preview.warnings()).anyMatch(warning -> warning.contains("乙节"));
    }

    @Test
    @DisplayName("引文取第一条有定位价值的:跳过「程序如下:」类引导语,全是引导语则不留")
    void skipsLeadInQuotes() {
        BuildPreview preview = GraphAssembler.assemble(ENTRIES, Map.of(),
                List.of(kp("变量", "变量名", "变量")), Map.of(0, 1), Set.of(1, 2), List.of(),
                List.of(new GraphAssembler.CodeExample("好例", "python", "x=1", "说明",
                                List.of("程序如下：", "x=1  //关键行"), List.of("变量")),
                        new GraphAssembler.CodeExample("废例", "python", "y=2", "说明",
                                List.of("代码如下所示：", "运行结果如下"), List.of("变量"))));

        var codes = preview.nodes().stream()
                .filter(node -> node.kind() == NodeKind.CODE_EXAMPLE).toList();
        assertThat(codes.get(0).quote()).isEqualTo("x=1  //关键行");
        assertThat(codes.get(1).quote()).isNull();
    }

    @Test
    @DisplayName("关系:端点缺失只警告;相关按键序规范化;同一对已有前置时相关被丢弃")
    void edges() {
        BuildPreview preview = GraphAssembler.assemble(ENTRIES, Map.of(),
                List.of(kp("甲"), kp("乙"), kp("丙")), Map.of(0, 1, 1, 1, 2, 2), Set.of(),
                List.of(new GraphRepairer.Relation("甲", "乙", EdgeKind.PREREQUISITE, "证据"),
                        new GraphRepairer.Relation("乙", "甲", EdgeKind.RELATED, "重复"),
                        new GraphRepairer.Relation("丙", "乙", EdgeKind.RELATED, ""),
                        new GraphRepairer.Relation("丙", "不存在", EdgeKind.RELATED, "")),
                List.of());

        assertThat(preview.edges()).extracting(PreviewEdge::sourceKey, PreviewEdge::targetKey, PreviewEdge::kind)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("k0", "k1", EdgeKind.PREREQUISITE),
                        org.assertj.core.groups.Tuple.tuple("k1", "k2", EdgeKind.RELATED));
        assertThat(preview.edges().get(0).evidence()).isEqualTo("证据");
        assertThat(preview.edges().get(1).evidence()).isNull();
        assertThat(preview.warnings()).anyMatch(warning -> warning.contains("1 条关系的端点"));
    }

    @Test
    @DisplayName("代码示例绑定不到知识点时剔除并警告;绑定多个时挂第一个并警告")
    void codeExamples() {
        BuildPreview preview = GraphAssembler.assemble(ENTRIES, Map.of(),
                List.of(kp("甲"), kp("乙")), Map.of(0, 1, 1, 1), Set.of(), List.of(),
                List.of(new GraphAssembler.CodeExample("孤例", "python", "x=1", "说明", List.of(), List.of("不存在")),
                        new GraphAssembler.CodeExample("双绑", "python", "y=2", "说明", List.of(), List.of("乙", "甲"))));

        List<PreviewNode> codes = preview.nodes().stream().filter(node -> node.kind() == NodeKind.CODE_EXAMPLE).toList();
        assertThat(codes).extracting(PreviewNode::key, PreviewNode::label, PreviewNode::parentKey)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("c0", "双绑", "k1"));
        assertThat(preview.warnings()).anyMatch(warning -> warning.contains("孤例"))
                .anyMatch(warning -> warning.contains("双绑") && warning.contains("乙"));
    }
}
