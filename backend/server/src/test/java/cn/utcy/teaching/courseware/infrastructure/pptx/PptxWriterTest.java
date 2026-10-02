package cn.utcy.teaching.courseware.infrastructure.pptx;

import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.domain.layout.LayoutEngine;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFChart;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导出回环:写出 pptx 再用 POI 读回,断言版面文本、备注、图片、原生图表都在,
 * 且测验答案绝不出现在幻灯片版面上(只进备注)。
 */
class PptxWriterTest {

    private static byte[] bytes;

    @BeforeAll
    static void export() {
        PptxWriter writer = new PptxWriter(new LayoutEngine(), org.mockito.Mockito.mock(cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage.class));
        bytes = writer.write(sampleStage());
        assertThat(bytes.length).isGreaterThan(10_000);
    }

    private static Stage sampleStage() {
        Stage.Scene cover = new Stage.Scene("p1", "content", "光的反射", "title-cover", "封面",
                List.of(new Block.Paragraph("blk-paragraph-1", "初中物理 · 第四章")),
                List.of(new Stage.SpeechSegment("同学们好,今天讲光的反射。", List.of(), null)), List.of(), null);

        Stage.Scene content = new Stage.Scene("p2", "content", "反射定律", "standard", "讲定律",
                List.of(
                        new Block.Heading("blk-heading-1", 2, "三条要点"),
                        new Block.Bullets("blk-bullets-1", null, List.of(
                                new Block.BulletItem("入射光线与反射光线分居法线两侧", null),
                                new Block.BulletItem("反射角**等于**入射角,即 $\\theta_r = \\theta_i$",
                                        List.of("这是核心结论")))),
                        new Block.Formula("blk-formula-1", "\\theta_r = \\theta_i", "反射定律")),
                List.of(new Stage.SpeechSegment("看这三条要点。", List.of(), null)), List.of(), null);

        Stage.Scene media = new Stage.Scene("p3", "content", "数据对比", "media-right", "看图",
                List.of(
                        new Block.Bullets("blk-bullets-1", null,
                                List.of(new Block.BulletItem("镜面反射更集中", null))),
                        new Block.Chart("blk-chart-1", "bar", List.of("镜面", "漫反射"),
                                List.of(new Block.ChartSeries("反射率", List.of(95.0, 60.0))), "反射率对比")),
                List.of(), List.of(), null);

        Stage.Scene emphasis = new Stage.Scene("p4", "content", "一句话记住", "standard", "点题",
                List.of(new Block.Emphasis("blk-emphasis-1", "反射角永远等于入射角", "反射定律的核心")),
                List.of(), List.of(), null);

        Stage.Scene quiz = new Stage.Scene("p5", "quiz", "随堂测验", "quiz", "考一考",
                List.of(new Block.QuizChoice("blk-quiz_choice-1", "反射角等于多少?",
                        List.of(new Block.QuizOption("A", "入射角"), new Block.QuizOption("B", "折射角")),
                        List.of("A"), false, "反射定律的直接结论就是答案A本身")),
                List.of(new Stage.SpeechSegment("请作答。", List.of(), null)), List.of(), null);

        return new Stage("光的反射", "default",
                List.of(cover, content, media, emphasis, quiz));
    }

    private static XMLSlideShow reopen() throws Exception {
        return new XMLSlideShow(new ByteArrayInputStream(bytes));
    }

    private static String slideText(XSLFSlide slide) {
        StringBuilder sb = new StringBuilder();
        for (XSLFShape shape : slide.getShapes()) {
            if (shape instanceof XSLFTextShape text) {
                sb.append(text.getText()).append('\n');
            }
            if (shape instanceof XSLFTable table) {
                table.getRows().forEach(r -> r.getCells().forEach(c -> sb.append(c.getText()).append(' ')));
            }
        }
        return sb.toString();
    }

    @Test
    @DisplayName("五页全部导出,版面文本齐全")
    void slidesCarryLayoutText() throws Exception {
        try (XMLSlideShow ppt = reopen()) {
            assertThat(ppt.getSlides()).hasSize(5);
            assertThat(slideText(ppt.getSlides().get(0))).contains("光的反射").contains("初中物理");
            String content = slideText(ppt.getSlides().get(1));
            assertThat(content).contains("三条要点").contains("等于").contains("\\theta_r = \\theta_i");
            assertThat(slideText(ppt.getSlides().get(4))).contains("反射角等于多少?").contains("入射角");
        }
    }

    @Test
    @DisplayName("测验答案与讲解只进备注,不进版面")
    void quizSecretsOnlyInNotes() throws Exception {
        try (XMLSlideShow ppt = reopen()) {
            XSLFSlide quizSlide = ppt.getSlides().get(4);
            assertThat(slideText(quizSlide)).doesNotContain("反射定律的直接结论");
            StringBuilder notes = new StringBuilder();
            for (XSLFTextShape shape : ppt.getNotesSlide(quizSlide).getPlaceholders()) {
                notes.append(shape.getText());
            }
            assertThat(notes.toString()).contains("【答案】A").contains("反射定律的直接结论");
        }
    }

    @Test
    @DisplayName("讲稿进演讲者备注")
    void speechGoesToNotes() throws Exception {
        try (XMLSlideShow ppt = reopen()) {
            StringBuilder notes = new StringBuilder();
            for (XSLFTextShape shape : ppt.getNotesSlide(ppt.getSlides().get(0)).getPlaceholders()) {
                notes.append(shape.getText());
            }
            assertThat(notes.toString()).contains("同学们好,今天讲光的反射。");
        }
    }

    @Test
    @DisplayName("强调文字落为版面文本,图表为原生 chart")
    void mediaBlocksExported() throws Exception {
        try (XMLSlideShow ppt = reopen()) {
            assertThat(slideText(ppt.getSlides().get(3))).contains("反射角永远等于入射角").contains("反射定律的核心");

            boolean hasChart = ppt.getSlides().get(2).getRelations().stream()
                    .anyMatch(r -> r instanceof XSLFChart);
            assertThat(hasChart).as("chart 页应有原生图表").isTrue();
        }
    }
}
