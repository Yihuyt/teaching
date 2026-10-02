package cn.utcy.teaching.courseware.domain.layout;

import cn.utcy.teaching.courseware.domain.Block;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 单块高度测量 —— 与 frontend/src/features/courseware/layout/measureBlock.ts 逐行对应。
 * chart 是"弹性块"(先占最小高度,布局时吃区域剩余);其余高度固定。
 */
public final class BlockMeasurer {

    private BlockMeasurer() {
    }

    public sealed interface MeasuredHeight {
        record Fixed(double h) implements MeasuredHeight {
        }

        record Flex(double minH) implements MeasuredHeight {
        }
    }

    /** bullets 一级条目符号/序号的缩进宽度(em,相对正文字号) */
    private static final double BULLET_INDENT_EM = 1.4;
    /** bullets 二级条目缩进(em) */
    private static final double SUB_INDENT_EM = 2.8;
    /** 代码块内边距(上下各一份) */
    private static final double CODE_PADDING = 14;
    /** callout 内边距 */
    private static final double CALLOUT_PADDING = 16;
    /** 表格单元格上下内边距合计 */
    private static final double CELL_PADDING_Y = 14;
    /** 表格单元格左右内边距合计 */
    private static final double CELL_PADDING_X = 20;
    /** 图片块:最大高度与尺寸未知时的默认高度(与 TS measureBlock.ts 同值) */
    static final double IMAGE_MAX_HEIGHT = 300;
    static final double IMAGE_DEFAULT_HEIGHT = 180;

    /** TS 源:latex.split('\\\\') —— 按字面量两个反斜杠切分,保留尾部空段(JS split 语义) */
    private static final Pattern FORMULA_ROW_SPLIT = Pattern.compile(Pattern.quote("\\\\"));

    public static MeasuredHeight measureLeafBlock(Block block, double width, double scale) {
        double fsH1 = Theme.FONT_H1 * scale;
        double fsH2 = Theme.FONT_H2 * scale;
        double fsBody = Theme.FONT_BODY * scale;
        double fsSmall = Theme.FONT_SMALL * scale;
        double fsCode = Theme.FONT_CODE * scale;
        double lh = Theme.LINE_HEIGHT;

        switch (block) {
            case Block.Heading b -> {
                double size = b.level() == 1 ? fsH1 : fsH2;
                return new MeasuredHeight.Fixed(TextMeasure.textHeight(b.text(), size, width, lh));
            }

            case Block.Paragraph b -> {
                return new MeasuredHeight.Fixed(TextMeasure.textHeight(b.text(), fsBody, width, lh));
            }

            case Block.Bullets b -> {
                double h = 0;
                double itemGap = Theme.SPACING_XS * scale;
                double indent = fsBody * BULLET_INDENT_EM;
                double subIndent = fsBody * SUB_INDENT_EM;
                List<Block.BulletItem> items = b.items();
                for (int i = 0; i < items.size(); i++) {
                    Block.BulletItem item = items.get(i);
                    if (i > 0) h += itemGap;
                    h += TextMeasure.textHeight(item.text(), fsBody, width - indent, lh);
                    for (String sub : item.sub() == null ? List.<String>of() : item.sub()) {
                        h += TextMeasure.textHeight(sub, fsSmall, width - subIndent, lh);
                    }
                }
                return new MeasuredHeight.Fixed(h);
            }

            case Block.Formula b -> {
                // 展示级公式:按 \\ 换行数估行,单行高约 2.2 倍正文字号
                int rows = FORMULA_ROW_SPLIT.split(b.latex(), -1).length;
                double h = Math.max(70 * scale, rows * fsBody * 2.2);
                if (b.caption() != null) {
                    h += Theme.SPACING_XS * scale + TextMeasure.textHeight(b.caption(), fsSmall, width, lh);
                }
                return new MeasuredHeight.Fixed(h);
            }

            case Block.Code b -> {
                double charEm = 0.6; // 等宽字宽
                double maxCols = Math.max(10, Math.floor(width / (fsCode * charEm)));
                double lines = 0;
                // JS split('\n') 保留尾部空段;raw.length 为 UTF-16 长度,与 Java String.length() 一致
                for (String raw : b.code().split("\n", -1)) {
                    lines += Math.max(1, Math.ceil(raw.length() / maxCols));
                }
                double h = lines * fsCode * Theme.CODE_LINE_HEIGHT + CODE_PADDING * 2 * scale;
                if (b.caption() != null) {
                    h += Theme.SPACING_XS * scale + TextMeasure.textHeight(b.caption(), fsSmall, width, lh);
                }
                return new MeasuredHeight.Fixed(h);
            }

            case Block.Table b -> {
                double colW = (width - CELL_PADDING_X * b.headers().size()) / b.headers().size();
                double h = tableRowHeight(b.headers(), fsSmall, colW, lh, scale);
                for (List<String> row : b.rows()) {
                    h += tableRowHeight(row, fsSmall, colW, lh, scale);
                }
                if (b.caption() != null) {
                    h += Theme.SPACING_XS * scale + TextMeasure.textHeight(b.caption(), fsSmall, width, lh);
                }
                return new MeasuredHeight.Fixed(h);
            }

            case Block.Chart b -> {
                double minH = 220 * scale;
                if (b.caption() != null) {
                    minH += Theme.SPACING_XS * scale + TextMeasure.textHeight(b.caption(), fsSmall, width, lh);
                }
                return new MeasuredHeight.Flex(minH);
            }

            case Block.Emphasis b -> {
                // 强调文字按一级标题字号排
                double h = TextMeasure.textHeight(b.text(), fsH1, width, lh);
                if (b.caption() != null) {
                    h += Theme.SPACING_XS * scale + TextMeasure.textHeight(b.caption(), fsSmall, width, lh);
                }
                return new MeasuredHeight.Fixed(h);
            }

            case Block.Image b -> {
                // 按原始宽高比排版:高度 = 宽 / 宽高比,封顶 IMAGE_MAX_HEIGHT;尺寸未知按默认高
                double ratio = b.width() > 0 && b.height() > 0 ? (double) b.width() / b.height() : 0;
                double h = ratio > 0 ? Math.min(width / ratio, IMAGE_MAX_HEIGHT * scale)
                        : IMAGE_DEFAULT_HEIGHT * scale;
                if (b.caption() != null) {
                    h += Theme.SPACING_XS * scale + TextMeasure.textHeight(b.caption(), fsSmall, width, lh);
                }
                return new MeasuredHeight.Fixed(h);
            }

            case Block.Callout b -> {
                // 视图无 title 时也会渲染变体标签行("提示"等),测量必须始终含标题行
                double innerW = width - CALLOUT_PADDING * 2 * scale;
                double h = CALLOUT_PADDING * 2 * scale;
                h += TextMeasure.textHeight(b.title() != null ? b.title() : "提示", fsBody, innerW, lh)
                        + Theme.SPACING_XS * scale;
                h += TextMeasure.textHeight(b.text(), fsSmall, innerW, lh);
                return new MeasuredHeight.Fixed(h);
            }

            case Block.QuizChoice b -> {
                // 与 QuizChoiceView 对应:选项框内边距 8px×2 + 边框 1.5px×2,作答模式始终预留提交按钮行
                final double OPTION_BOX_EXTRA = 19;
                final double BUTTON_RESERVE = 56;
                double optionIndent = fsBody * 2.2 + 12 * 2 * scale;
                double h = TextMeasure.textHeight(b.stem(), fsBody, width, lh);
                h += Theme.SPACING_SM * scale;
                List<Block.QuizOption> options = b.options();
                for (int i = 0; i < options.size(); i++) {
                    if (i > 0) h += Theme.SPACING_XS * scale;
                    h += TextMeasure.textHeight(options.get(i).text(), fsBody, width - optionIndent, lh)
                            + OPTION_BOX_EXTRA * scale;
                }
                h += Theme.SPACING_SM * scale + BUTTON_RESERVE * scale;
                // explanation 不参与页面排版(作答后由播放器覆盖层展示)
                return new MeasuredHeight.Fixed(h);
            }

            case Block.Columns ignored ->
                    throw new IllegalArgumentException("columns 是容器块,不能按叶子块测量");
        }
    }

    private static double tableRowHeight(List<String> cells, double size, double colW, double lh, double scale) {
        double maxLines = 1;
        for (String cell : cells) {
            maxLines = Math.max(maxLines, TextMeasure.countLines(cell, size, colW));
        }
        return maxLines * size * lh + CELL_PADDING_Y * scale;
    }
}
