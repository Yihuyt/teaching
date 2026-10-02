package cn.utcy.teaching.courseware.domain.layout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 与 frontend/tests/unit/courseware/layout-measure.test.ts 逐条对应 —— 断言值与 TS 端完全相同。
 */
class TextMeasureTest {

    @Test
    @DisplayName("charWidthEm:CJK 为 1em,ASCII 分类,数字 0.6")
    void charWidths() {
        assertEquals(1.0, TextMeasure.charWidthEm('中'));
        assertEquals(1.0, TextMeasure.charWidthEm('。'));
        assertEquals(0.35, TextMeasure.charWidthEm('i'));
        assertEquals(0.85, TextMeasure.charWidthEm('m'));
        assertEquals(0.6, TextMeasure.charWidthEm('8'));
        assertEquals(0.52, TextMeasure.charWidthEm('a'));
        assertEquals(0.3, TextMeasure.charWidthEm(' '));
    }

    @Test
    @DisplayName("stripInline:去除加粗星号,保留公式内容")
    void stripInline() {
        assertEquals("牛顿第二定律 F=ma 成立", TextMeasure.stripInline("牛顿**第二**定律 $F=ma$ 成立"));
    }

    // 24px 字号、480px 宽 → 每行 20em(与 TS 金标准断行用例一致)
    private static final double FS = 24;
    private static final double W = 480;

    @Test
    @DisplayName("纯 CJK:40 个汉字排 2 行")
    void pureCjkTwoLines() {
        String text = "这是一段用来测试断行的中文文本共计四十个汉字整整齐齐排成两行没有多余也没有缺少啊";
        assertEquals(40, text.codePointCount(0, text.length()));
        assertEquals(2, TextMeasure.countLines(text, FS, W));
    }

    @Test
    @DisplayName("空文本 0 行,单字 1 行")
    void emptyAndSingle() {
        assertEquals(0, TextMeasure.countLines("", FS, W));
        assertEquals(1, TextMeasure.countLines("中", FS, W));
    }

    @Test
    @DisplayName("中英混排:西文单词不拆")
    void latinWordsNotSplit() {
        List<String> lines = TextMeasure.wrapText(
                "速度 velocity 与加速度 acceleration 是两个不同的物理量需要仔细区分", FS, W);
        for (String line : lines) {
            // 每行中的字母串都应是完整单词
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[A-Za-z]+").matcher(line);
            while (m.find()) {
                String word = m.group();
                assertTrue(word.equals("velocity") || word.equals("acceleration"),
                        "单词被截断:" + word);
            }
        }
    }

    @Test
    @DisplayName("超长 URL 硬拆不丢字符")
    void urlForceSplitPreservesChars() {
        String url = "https://example.com/very/long/path/that/never/ends/and/keeps/going/forever/and/ever";
        List<String> lines = TextMeasure.wrapText(url, FS, W);
        assertTrue(lines.size() > 1);
        assertEquals(url, String.join("", lines));
    }

    @Test
    @DisplayName("行首不出现闭合标点")
    void noLeadingClosingPunct() {
        String text = "第一句话说完了。第二句话紧跟其后,并且带着标点。第三句继续,逗号也算。结束!";
        for (String line : TextMeasure.wrapText(text, FS, W)) {
            int first = line.codePointAt(0);
            assertFalse(first == '。' || first == ',' || first == '!' || first == '?' || first == ';',
                    "行首出现闭合标点:" + line);
        }
    }

    @Test
    @DisplayName("每行宽度不超过限制(闭合标点例外)")
    void lineWidthWithinLimit() {
        String text = "排版引擎必须保证每一行的估算宽度都在给定的最大宽度之内否则渲染就会溢出容器边界";
        for (String line : TextMeasure.wrapText(text, FS, W)) {
            double em = TextMeasure.textWidthEm(line);
            int last = line.codePointBefore(line.length());
            double allowance = TextMeasure.CLOSING_PUNCT.contains(last) ? 1 : 0;
            assertTrue(em <= W / FS + allowance + 1e-9, "行超宽:" + line);
        }
    }

    @Test
    @DisplayName("行数单调性:更窄的容器行数不减")
    void monotonicity() {
        String text = "同一段文本在不同宽度的容器里排版时容器越窄行数一定不会变少这是断行算法的基本性质";
        int wide = TextMeasure.countLines(text, FS, 800);
        int narrow = TextMeasure.countLines(text, FS, 400);
        assertTrue(narrow >= wide);
    }
}
