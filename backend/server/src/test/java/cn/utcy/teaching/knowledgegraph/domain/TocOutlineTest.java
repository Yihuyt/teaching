package cn.utcy.teaching.knowledgegraph.domain;

import cn.utcy.teaching.shared.error.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TocOutlineTest {

    private final List<TocEntry> entries = List.of(
            new TocEntry("第1章", "初识程序", 1, 5, 15),
            new TocEntry("1.1", "什么是程序", 2, 5, 9),
            new TocEntry("1.2", "第一个程序", 2, 9, 15),
            new TocEntry("第2章", "变量", 1, 15, 200));

    @Test
    @DisplayName("合法确认稿通过;叶子为无更深后继的条目")
    void validatesAndEnumeratesLeaves() {
        TocOutline.validate(entries, 200);
        assertThat(TocOutline.leafIndexes(entries)).containsExactly(1, 2, 3);
        assertThat(TocOutline.path(entries, 2)).isEqualTo("第1章 初识程序 > 1.2 第一个程序");
    }

    @Test
    @DisplayName("层级跳变被拒绝(相对前一条最多加深一级)")
    void rejectsLevelJump() {
        assertThatThrownBy(() -> TocOutline.validate(List.of(
                new TocEntry("第1章", "初识程序", 1, 5, 200),
                new TocEntry("1.1.1", "跳级", 3, 6, 200)), 200))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("层级跳变");
    }

    @Test
    @DisplayName("页码递减与越界被拒绝")
    void rejectsBadPages() {
        assertThatThrownBy(() -> TocOutline.validate(List.of(
                new TocEntry("第1章", "甲", 1, 9, 200),
                new TocEntry("第2章", "乙", 1, 5, 200)), 200))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("非递减");
        assertThatThrownBy(() -> TocOutline.validate(List.of(
                new TocEntry("第1章", "甲", 1, 300, 300)), 200))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("页码必须在 1~200 之间");
        assertThatThrownBy(() -> TocOutline.validate(List.of(
                new TocEntry("第1章", "甲", 1, 9, 5)), 200))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("止页必须在起页 9 与全书末页 200 之间");
    }

    @Test
    @DisplayName("叶子止页越过下一叶子的起页被拒绝(边界页共享允许):避免两节正文重复抽取")
    void rejectsOverlappingLeafRanges() {
        assertThatThrownBy(() -> TocOutline.validate(List.of(
                new TocEntry("1.1", "甲", 1, 1, 5),
                new TocEntry("1.2", "乙", 1, 4, 8)), 200))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("止页 5 越过了下一小节「乙」的起页 4");
        TocOutline.validate(List.of(
                new TocEntry("1.1", "甲", 1, 1, 4),
                new TocEntry("1.2", "乙", 1, 4, 8)), 200);
        // 父条目的止页覆盖整章,不参与叶子重叠判定
        TocOutline.validate(List.of(
                new TocEntry("第1章", "章", 1, 1, 8),
                new TocEntry("1.1", "甲", 2, 1, 4),
                new TocEntry("1.2", "乙", 2, 4, 8)), 200);
    }
}
