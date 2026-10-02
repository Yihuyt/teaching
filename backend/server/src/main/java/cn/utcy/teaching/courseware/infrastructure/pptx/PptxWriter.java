package cn.utcy.teaching.courseware.infrastructure.pptx;

import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.domain.layout.LayoutEngine;
import cn.utcy.teaching.courseware.domain.layout.Theme;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.sl.usermodel.Placeholder;
import org.apache.poi.sl.usermodel.ShapeType;
import org.apache.poi.sl.usermodel.TableCell;
import org.apache.poi.sl.usermodel.TextParagraph;
import org.apache.poi.xddf.usermodel.chart.AxisPosition;
import org.apache.poi.xddf.usermodel.chart.BarDirection;
import org.apache.poi.xddf.usermodel.chart.ChartTypes;
import org.apache.poi.xddf.usermodel.chart.LegendPosition;
import org.apache.poi.xddf.usermodel.chart.XDDFBarChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFCategoryAxis;
import org.apache.poi.xddf.usermodel.chart.XDDFChartData;
import org.apache.poi.xddf.usermodel.chart.XDDFChartLegend;
import org.apache.poi.xddf.usermodel.chart.XDDFDataSourcesFactory;
import org.apache.poi.xddf.usermodel.chart.XDDFValueAxis;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFChart;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage → pptx。几何完全复用布局引擎的帧(与屏幕渲染同一坐标来源,1px = 1pt,
 * 幻灯片画布即 1280×720pt);讲稿与测验答案进演讲者备注,不印在版面上。
 * 图表用 pptx 原生 chart(教师可在 PowerPoint 里继续改数据),异常时降级为数据表格;
 * 公式无 OMML 依赖(SnuggleTeX 不可得),按既定降级导出为数学斜体文本。
 */
@Component
public class PptxWriter {

    private static final Logger log = LoggerFactory.getLogger(PptxWriter.class);

    private static final String BODY_FONT = "Microsoft YaHei";
    private static final String CODE_FONT = "Consolas";
    private static final String MATH_FONT = "Cambria Math";

    static final Color TEXT = new Color(0x1f2329);
    static final Color MUTED = new Color(0x646a73);
    static final Color PRIMARY = new Color(0x2563eb);
    static final Color CODE_BG = new Color(0xf6f8fa);
    static final Color TABLE_BORDER = new Color(0xd0d7de);
    static final Color TABLE_HEADER_BG = new Color(0xf0f4f8);

    static final Map<String, Color[]> CALLOUT_COLORS = Map.of(
            "info", new Color[]{new Color(0x2563eb), new Color(0xeff6ff)},
            "tip", new Color[]{new Color(0x16a34a), new Color(0xf0fdf4)},
            "warning", new Color[]{new Color(0xd97706), new Color(0xfffbeb)},
            "conclusion", new Color[]{new Color(0x7c3aed), new Color(0xf5f3ff)});

    static final Color[] CHART_PALETTE = {
            new Color(0x2563eb), new Color(0xf59e0b), new Color(0x16a34a),
            new Color(0xdc2626), new Color(0x7c3aed), new Color(0x0891b2)};

    private final LayoutEngine layout;
    private final cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage assets;

    public PptxWriter(LayoutEngine layout, cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage assets) {
        this.layout = layout;
        this.assets = assets;
    }

    public byte[] write(Stage stage) {
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension(1280, 720));
            for (Stage.Scene scene : stage.scenes()) {
                renderScene(ppt, scene);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ppt.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("pptx 写出失败", e);
        }
    }

    // ---- 页面 ----

    private void renderScene(XMLSlideShow ppt, Stage.Scene scene) {
        XSLFSlide slide = ppt.createSlide();
        LayoutEngine.PositionedScene positioned = layout.layoutScene(scene);

        renderTitle(slide, scene, positioned.title());

        if (Stage.isBlockless(scene.type())) {
            renderPlaceholder(slide, positioned.frames().get(0), "video".equals(scene.type())
                    ? "本页是视频,请在智能课堂中在线观看" : "本页是交互仿真实验,请在智能课堂中打开体验");
        } else {
            Map<String, Block> byId = indexBlocks(scene.blocks());
            for (LayoutEngine.Frame frame : positioned.frames()) {
                Block block = byId.get(frame.blockId());
                if (block == null || block instanceof Block.Columns) {
                    continue; // columns 容器不渲染,子块有自己的帧
                }
                renderBlock(ppt, slide, block, rect(frame), frame.fontScale());
            }
        }

        writeNotes(ppt, slide, scene);
    }

    /** columns 的子块摊平进索引(帧列表里子块以自身 id 出现) */
    private static Map<String, Block> indexBlocks(List<Block> blocks) {
        Map<String, Block> map = new HashMap<>();
        for (Block block : blocks == null ? List.<Block>of() : blocks) {
            map.put(block.id(), block);
            if (block instanceof Block.Columns columns) {
                for (List<Block> col : columns.children()) {
                    for (Block child : col) {
                        map.put(child.id(), child);
                    }
                }
            }
        }
        return map;
    }

    private void renderTitle(XSLFSlide slide, Stage.Scene scene, LayoutEngine.TitleFrame title) {
        if (title == null) {
            return;
        }
        XSLFTextBox box = textBox(slide,
                new Rectangle2D.Double(title.x(), title.y(), title.w(), title.h() + 20));
        XSLFTextParagraph p = box.addNewTextParagraph();
        p.setTextAlign("center".equals(title.align())
                ? TextParagraph.TextAlign.CENTER : TextParagraph.TextAlign.LEFT);
        inlineRuns(p, scene.title(), title.fontSize(), TEXT, true);

        // 与前端一致:左对齐标题带一条主色下划线短杠
        if ("left".equals(title.align())) {
            XSLFAutoShape bar = slide.createAutoShape();
            bar.setShapeType(ShapeType.ROUND_RECT);
            bar.setAnchor(new Rectangle2D.Double(title.x(), title.y() + title.h() + 10, 64, 4));
            bar.setFillColor(PRIMARY);
            bar.setLineColor(PRIMARY);
        }
    }

    private void renderPlaceholder(XSLFSlide slide, LayoutEngine.Frame frame, String text) {
        XSLFAutoShape shape = slide.createAutoShape();
        shape.setShapeType(ShapeType.ROUND_RECT);
        shape.setAnchor(rect(frame));
        shape.setFillColor(new Color(0xf5f6f7));
        shape.setLineColor(TABLE_BORDER);
        XSLFTextParagraph p = shape.addNewTextParagraph();
        p.setTextAlign(TextParagraph.TextAlign.CENTER);
        run(p, text, Theme.FONT_SMALL, MUTED, false, false);
    }

    // ---- 块分派 ----

    private void renderBlock(XMLSlideShow ppt, XSLFSlide slide, Block block,
                             Rectangle2D frame, double scale) {
        switch (block) {
            case Block.Heading b -> renderHeading(slide, b, frame, scale);
            case Block.Paragraph b -> renderParagraph(slide, b, frame, scale);
            case Block.Bullets b -> renderBullets(slide, b, frame, scale);
            case Block.Formula b -> renderFormula(slide, b, frame, scale);
            case Block.Code b -> renderCode(slide, b, frame, scale);
            case Block.Table b -> renderTable(slide, b.headers(), b.rows(), b.caption(), frame, scale);
            case Block.Chart b -> renderChart(ppt, slide, b, frame, scale);
            case Block.Emphasis b -> renderEmphasis(slide, b, frame, scale);
            case Block.Image b -> renderImage(ppt, slide, b, frame, scale);
            case Block.Callout b -> renderCallout(slide, b, frame, scale);
            case Block.QuizChoice b -> renderQuiz(slide, b, frame, scale);
            case Block.Columns ignored -> { /* 容器不渲染 */ }
        }
    }

    private void renderHeading(XSLFSlide slide, Block.Heading b, Rectangle2D frame, double scale) {
        XSLFTextBox box = textBox(slide, frame);
        XSLFTextParagraph p = box.addNewTextParagraph();
        double size = (b.level() == 1 ? Theme.FONT_H1 : Theme.FONT_H2) * scale;
        inlineRuns(p, b.text(), size, TEXT, true);
    }

    private void renderParagraph(XSLFSlide slide, Block.Paragraph b, Rectangle2D frame, double scale) {
        XSLFTextBox box = textBox(slide, frame);
        inlineRuns(box.addNewTextParagraph(), b.text(), Theme.FONT_BODY * scale, TEXT);
    }

    private void renderBullets(XSLFSlide slide, Block.Bullets b, Rectangle2D frame, double scale) {
        XSLFTextBox box = textBox(slide, frame);
        int index = 1;
        for (Block.BulletItem item : b.items()) {
            XSLFTextParagraph p = box.addNewTextParagraph();
            p.setIndentLevel(0);
            if (Boolean.TRUE.equals(b.ordered())) {
                p.setBulletAutoNumber(org.apache.poi.sl.usermodel.AutoNumberingScheme.arabicPeriod, index);
            } else {
                p.setBullet(true);
                p.setBulletCharacter("•");
                p.setBulletFontColor(PRIMARY);
            }
            inlineRuns(p, item.text(), Theme.FONT_BODY * scale, TEXT);
            if (item.sub() != null) {
                for (String sub : item.sub()) {
                    XSLFTextParagraph sp = box.addNewTextParagraph();
                    sp.setIndentLevel(1);
                    sp.setBullet(true);
                    sp.setBulletCharacter("–");
                    sp.setBulletFontColor(MUTED);
                    inlineRuns(sp, sub, Theme.FONT_SMALL * scale, MUTED);
                }
            }
            index++;
        }
    }

    /** 公式降级:数学斜体文本(SnuggleTeX→OMML 不可得),排版上仍居中占位 */
    private void renderFormula(XSLFSlide slide, Block.Formula b, Rectangle2D frame, double scale) {
        Rectangle2D content = withCaption(slide, frame, b.caption(), scale);
        XSLFTextBox box = textBox(slide, content);
        box.setVerticalAlignment(org.apache.poi.sl.usermodel.VerticalAlignment.MIDDLE);
        XSLFTextParagraph p = box.addNewTextParagraph();
        p.setTextAlign(TextParagraph.TextAlign.CENTER);
        XSLFTextRun run = p.addNewTextRun();
        run.setText(b.latex());
        run.setFontFamily(MATH_FONT);
        run.setFontSize(Theme.FONT_H2 * scale);
        run.setItalic(true);
        run.setFontColor(TEXT);
    }

    private void renderCode(XSLFSlide slide, Block.Code b, Rectangle2D frame, double scale) {
        Rectangle2D content = withCaption(slide, frame, b.caption(), scale);
        XSLFAutoShape bg = slide.createAutoShape();
        bg.setShapeType(ShapeType.ROUND_RECT);
        bg.setAnchor(content);
        bg.setFillColor(CODE_BG);
        bg.setLineColor(TABLE_BORDER);
        bg.setTopInset(10);
        bg.setBottomInset(10);
        bg.setLeftInset(14);
        bg.setRightInset(14);
        bg.setVerticalAlignment(org.apache.poi.sl.usermodel.VerticalAlignment.TOP);
        for (String line : b.code().split("\n", -1)) {
            XSLFTextParagraph p = bg.addNewTextParagraph();
            p.setTextAlign(TextParagraph.TextAlign.LEFT);
            XSLFTextRun run = p.addNewTextRun();
            run.setText(line.isEmpty() ? " " : line);
            run.setFontFamily(CODE_FONT);
            run.setFontSize(Theme.FONT_CODE * scale);
            run.setFontColor(TEXT);
        }
    }

    private void renderTable(XSLFSlide slide, List<String> headers, List<List<String>> rows,
                             String caption, Rectangle2D frame, double scale) {
        renderTableInto(slide, headers, rows, withCaption(slide, frame, caption, scale), scale);
    }

    /** 表格主体(caption 由调用方处理——图表降级复用时不能重复画说明) */
    private void renderTableInto(XSLFSlide slide, List<String> headers, List<List<String>> rows,
                                 Rectangle2D content, double scale) {
        int cols = headers.size();
        XSLFTable table = slide.createTable(rows.size() + 1, cols);
        table.setAnchor(content);
        double colW = content.getWidth() / cols;
        for (int c = 0; c < cols; c++) {
            table.setColumnWidth(c, colW);
        }

        fillTableRow(table.getRows().get(0), headers, true, scale);
        for (int r = 0; r < rows.size(); r++) {
            fillTableRow(table.getRows().get(r + 1), rows.get(r), false, scale);
        }
    }

    private void fillTableRow(XSLFTableRow row, List<String> cells, boolean header, double scale) {
        row.setHeight(Theme.FONT_BODY * 1.2 * scale + 8);
        for (int c = 0; c < cells.size(); c++) {
            XSLFTableCell cell = row.getCells().get(c);
            cell.setFillColor(header ? TABLE_HEADER_BG : Color.WHITE);
            for (TableCell.BorderEdge edge : TableCell.BorderEdge.values()) {
                cell.setBorderColor(edge, TABLE_BORDER);
                cell.setBorderWidth(edge, 1.0);
            }
            XSLFTextParagraph p = cell.addNewTextParagraph();
            inlineRuns(p, cells.get(c), Theme.FONT_SMALL * scale, TEXT, header);
        }
    }

    /** 原生图表(PowerPoint 内可继续编辑);构建异常降级为数据表格 */
    private void renderChart(XMLSlideShow ppt, XSLFSlide slide, Block.Chart b,
                             Rectangle2D frame, double scale) {
        // caption 只画一次;记录形状数,失败时把已加入的空图表帧移除再降级
        Rectangle2D content = withCaption(slide, frame, b.caption(), scale);
        int shapesBefore = slide.getShapes().size();
        try {
            XSLFChart chart = ppt.createChart();
            slide.addChart(chart, content);

            // 原生图表渲染不了内联语法,与前端 ECharts 同策略:去标记纯文本
            String[] categories = b.categories().stream()
                    .map(InlineMarkup::strip).toArray(String[]::new);
            XDDFChartData data;
            if ("pie".equals(b.chartType())) {
                data = chart.createData(ChartTypes.PIE, null, null);
            } else {
                XDDFCategoryAxis bottom = chart.createCategoryAxis(AxisPosition.BOTTOM);
                XDDFValueAxis left = chart.createValueAxis(AxisPosition.LEFT);
                data = chart.createData(
                        "bar".equals(b.chartType()) ? ChartTypes.BAR : ChartTypes.LINE, bottom, left);
                if (data instanceof XDDFBarChartData bar) {
                    bar.setBarDirection(BarDirection.COL);
                }
            }
            data.setVaryColors("pie".equals(b.chartType()));

            // pie 语义与前端一致:只取第一个 series
            List<Block.ChartSeries> seriesList =
                    "pie".equals(b.chartType()) ? b.series().subList(0, 1) : b.series();
            for (Block.ChartSeries s : seriesList) {
                Double[] values = s.data().toArray(Double[]::new);
                XDDFChartData.Series series = data.addSeries(
                        XDDFDataSourcesFactory.fromArray(categories, null),
                        XDDFDataSourcesFactory.fromArray(values, null));
                series.setTitle(InlineMarkup.strip(s.name()), null);
            }
            chart.plot(data);
            if (!"pie".equals(b.chartType()) && b.series().size() > 1 || "pie".equals(b.chartType())) {
                XDDFChartLegend legend = chart.getOrAddLegend();
                legend.setPosition(LegendPosition.BOTTOM);
            }
        } catch (RuntimeException e) {
            log.warn("图表 {} 原生导出失败,降级为数据表格", b.id(), e);
            while (slide.getShapes().size() > shapesBefore) {
                slide.removeShape(slide.getShapes().get(slide.getShapes().size() - 1));
            }
            List<String> headers = new ArrayList<>();
            headers.add("");
            headers.addAll(b.categories());
            List<List<String>> rows = new ArrayList<>();
            for (Block.ChartSeries s : b.series()) {
                List<String> row = new ArrayList<>();
                row.add(s.name());
                for (Double v : s.data()) {
                    row.add(trimNumber(v));
                }
                rows.add(row);
            }
            renderTableInto(slide, headers, rows, content, scale);
        }
    }

    private void renderEmphasis(XSLFSlide slide, Block.Emphasis b, Rectangle2D frame, double scale) {
        Rectangle2D content = withCaption(slide, frame, b.caption(), scale);
        XSLFTextBox box = textBox(slide, content);
        box.setVerticalAlignment(org.apache.poi.sl.usermodel.VerticalAlignment.MIDDLE);
        XSLFTextParagraph p = box.addNewTextParagraph();
        p.setTextAlign(TextParagraph.TextAlign.CENTER);
        inlineRuns(p, b.text(), Theme.FONT_H1 * scale, PRIMARY, true);
    }

    /** 文档图片:从对象存储取字节内嵌,按原始宽高比居中放进帧(帧高已由测量按比例给出) */
    private void renderImage(XMLSlideShow ppt, XSLFSlide slide, Block.Image b,
                             Rectangle2D frame, double scale) {
        Rectangle2D content = withCaption(slide, frame, b.caption(), scale);
        try {
            byte[] bytes = assets.get(b.src());
            PictureData.PictureType type = pictureType(b.src());
            XSLFPictureData data = ppt.addPicture(bytes, type);
            XSLFPictureShape picture = slide.createPicture(data);
            Rectangle2D anchor = content;
            if (b.width() > 0 && b.height() > 0) {
                double ratio = (double) b.width() / b.height();
                double w = Math.min(content.getWidth(), content.getHeight() * ratio);
                double h = w / ratio;
                anchor = new Rectangle2D.Double(content.getX() + (content.getWidth() - w) / 2,
                        content.getY() + (content.getHeight() - h) / 2, w, h);
            }
            picture.setAnchor(anchor);
        } catch (RuntimeException e) {
            log.warn("图片 {} 导出失败,导出占位文本", b.id(), e);
            XSLFTextBox box = textBox(slide, content);
            box.setVerticalAlignment(org.apache.poi.sl.usermodel.VerticalAlignment.MIDDLE);
            XSLFTextParagraph p = box.addNewTextParagraph();
            p.setTextAlign(TextParagraph.TextAlign.CENTER);
            run(p, "[图片:" + (b.caption() == null ? b.id() : b.caption()) + "]",
                    Theme.FONT_SMALL * scale, MUTED, false, false);
        }
    }

    private static PictureData.PictureType pictureType(String key) {
        String lower = key.toLowerCase();
        if (lower.endsWith(".png")) {
            return PictureData.PictureType.PNG;
        }
        if (lower.endsWith(".gif")) {
            return PictureData.PictureType.GIF;
        }
        if (lower.endsWith(".bmp")) {
            return PictureData.PictureType.BMP;
        }
        return PictureData.PictureType.JPEG;
    }

    private void renderCallout(XSLFSlide slide, Block.Callout b, Rectangle2D frame, double scale) {
        Color[] colors = CALLOUT_COLORS.getOrDefault(b.variant(), CALLOUT_COLORS.get("info"));
        XSLFAutoShape shape = slide.createAutoShape();
        shape.setShapeType(ShapeType.ROUND_RECT);
        shape.setAnchor(frame);
        shape.setFillColor(colors[1]);
        shape.setLineColor(colors[0]);
        shape.setTopInset(10);
        shape.setBottomInset(10);
        shape.setLeftInset(14);
        shape.setRightInset(14);
        XSLFTextParagraph titleP = shape.addNewTextParagraph();
        titleP.setTextAlign(TextParagraph.TextAlign.LEFT);
        inlineRuns(titleP, b.title() == null || b.title().isBlank() ? "提示" : b.title(),
                Theme.FONT_BODY * scale, colors[0], true);
        XSLFTextParagraph textP = shape.addNewTextParagraph();
        textP.setTextAlign(TextParagraph.TextAlign.LEFT);
        inlineRuns(textP, b.text(), Theme.FONT_SMALL * scale, TEXT);
    }

    /** 题面只印题干与选项;answer/explanation 进备注(与 play 视图同一保密思路) */
    private void renderQuiz(XSLFSlide slide, Block.QuizChoice b, Rectangle2D frame, double scale) {
        XSLFTextBox box = textBox(slide, frame);
        XSLFTextParagraph stem = box.addNewTextParagraph();
        inlineRuns(stem, b.stem(), Theme.FONT_BODY * scale, TEXT);
        for (Block.QuizOption option : b.options()) {
            XSLFTextParagraph p = box.addNewTextParagraph();
            p.setSpaceBefore(8.0);
            run(p, option.label() + ". ", Theme.FONT_BODY * scale, PRIMARY, true, false);
            inlineRuns(p, option.text(), Theme.FONT_BODY * scale, TEXT);
        }
    }

    // ---- 备注 ----

    private void writeNotes(XMLSlideShow ppt, XSLFSlide slide, Stage.Scene scene) {
        List<String> parts = new ArrayList<>();
        if (scene.speech() != null) {
            for (Stage.SpeechSegment segment : scene.speech()) {
                parts.add(segment.text());
            }
        }
        for (Block block : scene.blocks() == null ? List.<Block>of() : scene.blocks()) {
            if (block instanceof Block.QuizChoice quiz) {
                parts.add("【答案】" + String.join("、", quiz.answer())
                        + (quiz.explanation() == null || quiz.explanation().isBlank()
                        ? "" : "\n【讲解】" + InlineMarkup.strip(quiz.explanation())));
            }
        }
        if (parts.isEmpty()) {
            return;
        }
        XSLFNotes notes = ppt.getNotesSlide(slide);
        for (XSLFTextShape shape : notes.getPlaceholders()) {
            if (shape.getTextType() == Placeholder.BODY) {
                shape.setText(String.join("\n\n", parts));
                return;
            }
        }
    }

    // ---- 文本工具 ----

    /**
     * 行内语法 → 带样式 run 序列。解析由 InlineMarkup 完成(与前端 parseInline 金样锁定),
     * 此处只做样式映射:bold=加粗,latex=数学斜体(OMML 依赖不可得的既定降级)。
     */
    private static void inlineRuns(XSLFTextParagraph p, String text, double fontSize, Color color) {
        inlineRuns(p, text, fontSize, color, false);
    }

    private static void inlineRuns(XSLFTextParagraph p, String text, double fontSize, Color color,
                                   boolean baseBold) {
        for (InlineMarkup.Segment seg : InlineMarkup.parse(text)) {
            boolean latex = "latex".equals(seg.kind());
            plainRun(p, seg.text(), fontSize, color,
                    baseBold || "bold".equals(seg.kind()), latex);
        }
    }

    private static void plainRun(XSLFTextParagraph p, String text, double fontSize, Color color,
                                 boolean bold, boolean math) {
        if (text.isEmpty()) {
            return;
        }
        XSLFTextRun run = p.addNewTextRun();
        run.setText(text);
        run.setFontFamily(math ? MATH_FONT : BODY_FONT);
        run.setFontSize(fontSize);
        run.setFontColor(color);
        run.setBold(bold);
        run.setItalic(math);
    }

    private static void run(XSLFTextParagraph p, String text, double fontSize, Color color,
                            boolean bold, boolean italic) {
        XSLFTextRun run = p.addNewTextRun();
        run.setText(text);
        run.setFontFamily(BODY_FONT);
        run.setFontSize(fontSize);
        run.setFontColor(color);
        run.setBold(bold);
        run.setItalic(italic);
    }

    private static XSLFTextBox textBox(XSLFSlide slide, Rectangle2D anchor) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(anchor);
        box.clearText();
        box.setWordWrap(true);
        box.setTopInset(0);
        box.setBottomInset(0);
        box.setLeftInset(0);
        box.setRightInset(0);
        return box;
    }

    private Rectangle2D withCaption(XSLFSlide slide, Rectangle2D frame, String caption, double scale) {
        if (caption == null || caption.isBlank()) {
            return frame;
        }
        double captionH = Theme.FONT_SMALL * 1.5 * scale + 8;
        Rectangle2D captionRect = new Rectangle2D.Double(
                frame.getX(), frame.getY() + frame.getHeight() - captionH,
                frame.getWidth(), captionH);
        XSLFTextBox box = textBox(slide, captionRect);
        XSLFTextParagraph p = box.addNewTextParagraph();
        p.setTextAlign(TextParagraph.TextAlign.CENTER);
        inlineRuns(p, caption, Theme.FONT_SMALL * scale, MUTED);
        return new Rectangle2D.Double(frame.getX(), frame.getY(),
                frame.getWidth(), frame.getHeight() - captionH);
    }

    private static Rectangle2D rect(LayoutEngine.Frame frame) {
        return new Rectangle2D.Double(frame.x(), frame.y(), frame.w(), frame.h());
    }

    private static String trimNumber(Double v) {
        if (v == null) {
            return "";
        }
        return v == Math.floor(v) ? String.valueOf(v.longValue()) : String.valueOf(v);
    }

}
