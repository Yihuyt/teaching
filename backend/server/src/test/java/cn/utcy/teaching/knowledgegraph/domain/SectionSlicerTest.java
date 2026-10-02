package cn.utcy.teaching.knowledgegraph.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SectionSlicerTest {

    @Test
    @DisplayName("叶子边界:区间取自条目自身的起止页,边界页共享")
    void sliceBoundaries() {
        List<TocEntry> entries = List.of(
                new TocEntry("第1章", "初识程序", 1, 2, 6),
                new TocEntry("1.1", "什么是程序", 2, 2, 4),
                new TocEntry("1.2", "第一个程序", 2, 4, 6),
                new TocEntry("第2章", "变量", 1, 6, 7));
        List<String> pages = List.of("p1", "p2", "p3", "p4", "p5", "p6", "p7");

        List<SectionSlicer.Slice> slices = SectionSlicer.slice(entries, pages, 24_000);

        assertThat(slices).hasSize(3);
        assertThat(slices.get(0).startPage()).isEqualTo(2);
        assertThat(slices.get(0).endPage()).isEqualTo(4);
        assertThat(slices.get(0).text()).isEqualTo("p2\n\np3\n\np4");
        assertThat(slices.get(1).startPage()).isEqualTo(4);
        assertThat(slices.get(1).endPage()).isEqualTo(6);
        assertThat(slices.get(2).startPage()).isEqualTo(6);
        assertThat(slices.get(2).endPage()).isEqualTo(7);
        assertThat(slices.get(2).path()).isEqualTo("第2章 变量");
    }

    @Test
    @DisplayName("超长节按段落均分为多片,标题带 (i/n) 后缀")
    void splitsOversizedLeaf() {
        List<TocEntry> entries = List.of(new TocEntry("第1章", "长章", 1, 1, 1));
        String paragraph = "内容段落。".repeat(30);
        List<String> pages = List.of((paragraph + "\n\n").repeat(10).strip());

        List<SectionSlicer.Slice> slices = SectionSlicer.slice(entries, pages, 800);

        assertThat(slices).hasSizeGreaterThan(1);
        assertThat(slices.get(0).title()).endsWith("(1/" + slices.size() + ")");
        for (SectionSlicer.Slice slice : slices) {
            assertThat(slice.text().length()).isLessThanOrEqualTo(800 + 2);
        }
        String joined = String.join("", slices.stream()
                .map(slice -> slice.text().replace("\n\n", "")).toList());
        assertThat(joined).isEqualTo(paragraph.repeat(10).replace("\n\n", ""));
    }
}
