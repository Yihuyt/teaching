package cn.utcy.teaching.knowledgebase.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RrfFusionTest {

    @Test
    @DisplayName("双路都靠前的结果融合后排第一")
    void agreementWins() {
        List<String> fused = RrfFusion.fuse(List.of(
                List.of("a", "b", "c"),
                List.of("b", "a", "d")), 3);
        // a: 1/61+1/62, b: 1/62+1/61 —— 并列;c/d 各只有一路
        assertThat(fused).hasSize(3);
        assertThat(fused.subList(0, 2)).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("单路独有的高名次结果不会被漏掉")
    void singleListHitSurvives() {
        List<String> fused = RrfFusion.fuse(List.of(
                List.of("x", "y"),
                List.of()), 5);
        assertThat(fused).containsExactly("x", "y");
    }

    @Test
    @DisplayName("limit 截断且保持融合分降序")
    void limitTruncates() {
        List<String> fused = RrfFusion.fuse(List.of(
                List.of("a", "b", "c", "d"),
                List.of("a", "c", "b", "d")), 2);
        assertThat(fused).hasSize(2);
        assertThat(fused.get(0)).isEqualTo("a");
    }

    @Test
    @DisplayName("空输入返回空列表")
    void emptyInput() {
        assertThat(RrfFusion.fuse(List.of(List.of(), List.of()), 5)).isEmpty();
    }
}
