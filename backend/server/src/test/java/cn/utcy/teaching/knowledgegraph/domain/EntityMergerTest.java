package cn.utcy.teaching.knowledgegraph.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EntityMergerTest {

    private static EntityMerger.RawKp kp(String name, String type, String definition, int section) {
        return new EntityMerger.RawKp(name, type, definition, List.of(),
                List.of(definition + "引文"), section);
    }

    @Test
    @DisplayName("规范化同名直接合并;canonical 取最长释义;别名与出处节合并")
    void exactMerge() {
        EntityMerger merger = new EntityMerger();
        merger.add(kp("For 循环", "概念", "短释义", 0));
        merger.add(kp("for循环", "概念", "这是一段更长的释义文本用于胜出", 2));
        merger.add(kp("while 循环", "概念", "另一个概念", 1));

        List<EntityMerger.CandidatePair> pairs = merger.exactMergeAndCollectCandidates();
        EntityMerger.MergeResult result = merger.finalizeMerge();

        assertThat(result.knowledgePoints()).hasSize(2);
        EntityMerger.MergedKp merged = result.knowledgePoints().stream()
                .filter(item -> item.name().contains("for") || item.name().contains("For"))
                .findFirst().orElseThrow();
        assertThat(merged.definition()).isEqualTo("这是一段更长的释义文本用于胜出");
        assertThat(merged.aliases()).contains("For 循环");
        assertThat(merged.sectionIndexes()).containsExactly(0, 2);
        assertThat(result.nameToCanonical().get("For 循环")).isEqualTo(merged.name());
        // while 与 for 名称相似度低,不产生候选对
        assertThat(pairs).isEmpty();
    }

    @Test
    @DisplayName("相似但不等的名字进入候选对,裁决合并后收敛,拒绝则保持独立")
    void candidateAdjudication() {
        EntityMerger merger = new EntityMerger();
        merger.add(kp("二次函数的图像", "概念", "释义甲", 0));
        merger.add(kp("二次函数图像", "概念", "释义乙更长一些", 1));
        merger.add(kp("一次函数", "概念", "无关概念", 2));

        List<EntityMerger.CandidatePair> pairs = merger.exactMergeAndCollectCandidates();
        assertThat(pairs).hasSize(1);
        merger.applyMerge(pairs.get(0));
        EntityMerger.MergeResult result = merger.finalizeMerge();

        assertThat(result.knowledgePoints()).hasSize(2);
        assertThat(result.nameToCanonical().get("二次函数的图像"))
                .isEqualTo(result.nameToCanonical().get("二次函数图像"));
    }

    @Test
    @DisplayName("同名不同小类一律合并,小类取首次出现(不再让图里出现重名节点)")
    void sameNameDifferentTypeMerges() {
        EntityMerger merger = new EntityMerger();
        merger.add(kp("排序", "概念", "概念释义", 0));
        merger.add(kp("排序", "方法", "更长一些的方法释义", 1));

        assertThat(merger.exactMergeAndCollectCandidates()).isEmpty();
        List<EntityMerger.MergedKp> merged = merger.finalizeMerge().knowledgePoints();
        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).kpType()).isEqualTo("概念");
        assertThat(merged.get(0).definition()).isEqualTo("更长一些的方法释义");
    }

    @Test
    @DisplayName("字面不相似但互为别名的(如 主函数/main函数)也进候选对,裁决后归一")
    void aliasOverlapBecomesCandidate() {
        EntityMerger merger = new EntityMerger();
        merger.add(new EntityMerger.RawKp("主函数", "概念", "程序入口。",
                java.util.List.of("main函数"), java.util.List.of("引文甲"), 0));
        merger.add(new EntityMerger.RawKp("main函数", "概念", "每个C++程序必须有的入口函数,从这里开始执行。",
                java.util.List.of("主函数"), java.util.List.of("引文乙"), 1));

        var candidates = merger.exactMergeAndCollectCandidates();
        org.assertj.core.api.Assertions.assertThat(candidates).hasSize(1);
        merger.applyMerge(candidates.get(0));
        var result = merger.finalizeMerge();

        org.assertj.core.api.Assertions.assertThat(result.knowledgePoints()).hasSize(1);
        var merged = result.knowledgePoints().get(0);
        org.assertj.core.api.Assertions.assertThat(merged.name()).isEqualTo("main函数");
        org.assertj.core.api.Assertions.assertThat(merged.aliases()).contains("主函数");
        org.assertj.core.api.Assertions.assertThat(result.nameToCanonical().get("主函数")).isEqualTo("main函数");
    }

    @Test
    @DisplayName("语义召回:余弦达标的近邻进候选,已捞过的去重,总量封顶;向量全零不召回")
    void semanticCandidatesRecall() {
        var roots = java.util.List.of(
                new EntityMerger.RootRef(0, "主函数", "程序入口", java.util.List.of()),
                new EntityMerger.RootRef(1, "main函数", "入口函数", java.util.List.of()),
                new EntityMerger.RootRef(2, "二维数组", "行列数组", java.util.List.of()));
        var vectors = java.util.List.of(
                new float[]{1f, 0f}, new float[]{0.97f, 0.24f}, new float[]{0f, 1f});

        var pairs = EntityMerger.semanticCandidates(roots, vectors, java.util.List.of(), 0.75, 3, 10, 7);
        org.assertj.core.api.Assertions.assertThat(pairs).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(pairs.get(0).pairId()).isEqualTo(7);
        org.assertj.core.api.Assertions.assertThat(pairs.get(0).leftName()).isEqualTo("主函数");
        org.assertj.core.api.Assertions.assertThat(pairs.get(0).rightName()).isEqualTo("main函数");

        // 已被别名网捞过 → 语义网去重
        var existing = java.util.List.of(pairs.get(0));
        org.assertj.core.api.Assertions.assertThat(
                EntityMerger.semanticCandidates(roots, vectors, existing, 0.75, 3, 10, 8)).isEmpty();

        // 全零向量不产生候选
        var zeros = java.util.List.of(new float[]{0f, 0f}, new float[]{0f, 0f}, new float[]{0f, 0f});
        org.assertj.core.api.Assertions.assertThat(
                EntityMerger.semanticCandidates(roots, zeros, java.util.List.of(), 0.75, 3, 10, 0)).isEmpty();
    }

    @Test
    @DisplayName("相似对不看小类,交裁决")
    void similarNamesAcrossTypesBecomeCandidates() {
        EntityMerger merger = new EntityMerger();
        merger.add(kp("冒泡排序", "方法", "释义", 0));
        merger.add(kp("冒泡排序法", "技能", "释义", 1));

        assertThat(merger.exactMergeAndCollectCandidates()).hasSize(1);
    }
}
