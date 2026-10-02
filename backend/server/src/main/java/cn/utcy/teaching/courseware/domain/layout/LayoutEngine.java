package cn.utcy.teaching.courseware.domain.layout;

import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.Stage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LayoutEngine {

    private static final double[] FONT_SCALES = {1, 0.92, 0.85, 0.78};

    /** media-right 预设中会被放到右侧媒体区的块类型 */
    private static final Set<String> MEDIA_TYPES = Set.of("chart", "image", "code", "table");

    /**
     * 一块的定位帧。fontScale:流式块 = 页级缩字 × 块字号档,钉住块 = 块字号档;
     * pinned:教师钉住的块(位置尺寸来自排版覆盖,不参与流式排版与页级缩字)。
     */
    public record Frame(String blockId, double x, double y, double w, double h, double fontScale, boolean pinned) {
    }

    public record TitleFrame(double x, double y, double w, double h,
                             double fontSize, String align, List<String> lines) {
    }

    /**
     * overflow: none = 原字号放下;shrunk = 缩字后放下;error = 最小档仍放不下或有钉住块底边出页。
     * frames:先各区域的流式帧,再钉住帧(按块顺序)。
     */
    public record PositionedScene(String sceneId, String preset, double fontScale,
                                 String overflow, TitleFrame title, List<Frame> frames) {
    }

    private record Band(double top, double bottom) {
    }

    private record FlowResult(List<Frame> frames, double usedH, boolean fits) {
    }

    private record Entry(Block block, double h, boolean flex, double fontScale, List<Frame> children) {
    }

    private record Split(Map<String, Stage.BlockLayout> layouts, List<Frame> pinned, List<Block> flow) {
    }

    /** 排版覆盖索引:只认引用顶层块的条目(校验保证如此;引擎对孤儿条目视而不见) */
    private static Map<String, Stage.BlockLayout> indexLayouts(Stage.Scene scene) {
        Map<String, Stage.BlockLayout> map = new HashMap<>();
        Set<String> topLevel = new HashSet<>();
        for (Block block : blocksOf(scene)) topLevel.add(block.id());
        for (Stage.BlockLayout layout : scene.layoutsOrEmpty()) {
            if (topLevel.contains(layout.blockId())) map.put(layout.blockId(), layout);
        }
        return map;
    }

    private static double sizeScale(Stage.BlockLayout layout) {
        return layout == null ? 1 : Theme.blockSizeScale(layout.size());
    }

    private static boolean isPinned(Stage.BlockLayout layout) {
        return layout != null && layout.frame() != null;
    }

    /** 钉住块成帧:位置与宽来自覆盖;image / chart 高来自覆盖,其余按内容在该宽度下量出 */
    private static Frame pinnedFrame(Block block, Stage.BlockLayout layout) {
        Stage.PinFrame frame = layout.frame();
        double scale = sizeScale(layout);
        double h;
        if (Stage.FREE_HEIGHT_TYPES.contains(block.type()) && frame.h() != null) {
            h = frame.h();
        } else {
            BlockMeasurer.MeasuredHeight m = BlockMeasurer.measureLeafBlock(block, frame.w(), scale);
            h = m instanceof BlockMeasurer.MeasuredHeight.Fixed f ? f.h()
                    : ((BlockMeasurer.MeasuredHeight.Flex) m).minH();
        }
        return new Frame(block.id(), frame.x(), frame.y(), frame.w(), h, scale, true);
    }

    private static List<Band> bandsFor(Presets.Region region, List<Frame> pinned, double gap) {
        List<Band> bands = new ArrayList<>();
        for (Frame f : pinned) {
            if (f.x() < region.x() + region.w() && f.x() + f.w() > region.x()) {
                bands.add(new Band(f.y() - gap, f.y() + f.h() + gap));
            }
        }
        bands.sort((a, b) -> Double.compare(a.top(), b.top()));
        return bands;
    }

    private static double skipBands(double y, double h, List<Band> bands) {
        boolean moved = true;
        while (moved) {
            moved = false;
            for (Band band : bands) {
                if (y < band.bottom() && y + h > band.top()) {
                    y = band.bottom();
                    moved = true;
                }
            }
        }
        return y;
    }

    private static FlowResult flowRegion(List<Block> blocks, Presets.Region region, double scale,
                                         Map<String, Stage.BlockLayout> layouts, List<Frame> pinned) {
        double gap = Theme.SPACING_MD * scale;
        List<Band> bands = bandsFor(region, pinned, gap);

        List<Entry> entries = new ArrayList<>();

        for (Block block : blocks) {
            double blockScale = scale * sizeScale(layouts.get(block.id()));
            if (block instanceof Block.Columns columns) {
                double colGap = Theme.SPACING_LG * blockScale;
                List<List<Block>> children = columns.children();
                List<Double> ratios = columns.ratio() != null && columns.ratio().size() == children.size()
                        ? columns.ratio()
                        : children.stream().map(c -> 1.0).toList();
                double ratioSum = 0;
                for (double r : ratios) ratioSum += r;
                double availW = region.w() - colGap * (children.size() - 1);

                List<Frame> columnFrames = new ArrayList<>();
                double maxColH = 0;
                double colX = region.x();
                for (int ci = 0; ci < children.size(); ci++) {
                    List<Block> col = children.get(ci);
                    double colW = (availW * ratios.get(ci)) / ratioSum;
                    double y = 0;
                    for (int bi = 0; bi < col.size(); bi++) {
                        Block child = col.get(bi);
                        if (bi > 0) y += Theme.SPACING_SM * blockScale;
                        BlockMeasurer.MeasuredHeight m = BlockMeasurer.measureLeafBlock(child, colW, blockScale);
                        double h = m instanceof BlockMeasurer.MeasuredHeight.Fixed f ? f.h()
                                : ((BlockMeasurer.MeasuredHeight.Flex) m).minH();
                        columnFrames.add(new Frame(child.id(), colX, y, colW, h, blockScale, false));
                        y += h;
                    }
                    maxColH = Math.max(maxColH, y);
                    colX += colW + colGap;
                }

                entries.add(new Entry(block, maxColH, false, blockScale, columnFrames));
                continue;
            }

            BlockMeasurer.MeasuredHeight m = BlockMeasurer.measureLeafBlock(block, region.w(), blockScale);
            if (m instanceof BlockMeasurer.MeasuredHeight.Fixed f) {
                entries.add(new Entry(block, f.h(), false, blockScale, null));
            } else {
                entries.add(new Entry(block, ((BlockMeasurer.MeasuredHeight.Flex) m).minH(), true, blockScale, null));
            }
        }

        FlowResult base = place(entries, region, gap, bands, 0);
        // 弹性块均分剩余高度(只在放得下时扩展;撑开后被钉住块顶出区域则不扩展)
        int flexCount = 0;
        for (Entry e : entries) if (e.flex()) flexCount++;
        double leftover = region.h() - base.usedH();
        if (flexCount > 0 && leftover > 0) {
            FlowResult expanded = place(entries, region, gap, bands, leftover / flexCount);
            if (expanded.fits()) return expanded;
        }
        return base;
    }

    private static FlowResult place(List<Entry> entries, Presets.Region region, double gap, List<Band> bands,
                                    double bonus) {
        List<Frame> frames = new ArrayList<>();
        double y = region.y();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (i > 0) y += gap;
            double h = e.flex() ? e.h() + bonus : e.h();
            y = skipBands(y, h, bands);
            frames.add(new Frame(e.block().id(), region.x(), y, region.w(), h, e.fontScale(), false));
            if (e.children() != null) {
                for (Frame child : e.children()) {
                    frames.add(new Frame(child.blockId(), child.x(), y + child.y(), child.w(), child.h(),
                            child.fontScale(), false));
                }
            }
            y += h;
        }
        return new FlowResult(frames, y - region.y(), y - region.y() <= region.h() + 0.5);
    }

    /** 把块分配到预设的各内容区(media-right:最后一个媒体类块进右区,其余进左区) */
    private static List<List<Block>> assignBlocks(List<Block> blocks, int regionCount) {
        if (regionCount == 1) return List.of(blocks);

        int mediaIndex = -1;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            if (MEDIA_TYPES.contains(blocks.get(i).type())) {
                mediaIndex = i;
                break;
            }
        }
        if (mediaIndex == -1) return List.of(blocks, List.of());
        List<Block> left = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            if (i != mediaIndex) left.add(blocks.get(i));
        }
        return List.of(left, List.of(blocks.get(mediaIndex)));
    }

    /** 注意:标题条取自页面声明的原始预设(即便内容区因降级换用 standard) */
    private static TitleFrame layoutTitle(Stage.Scene scene) {
        Presets.PresetDef preset = Presets.getPreset(scene.preset());
        if (preset.titleBar() == null) return null;
        Presets.TitleBar bar = preset.titleBar();

        double fontSize = bar.fontSize();
        List<String> lines = TextMeasure.wrapText(scene.title(), fontSize, bar.w());
        if (lines.size() > 2) {
            fontSize = Math.round(fontSize * 0.85);
            lines = TextMeasure.wrapText(scene.title(), fontSize, bar.w());
        }
        double h = Math.max(1, lines.size()) * fontSize * Theme.LINE_HEIGHT;
        return new TitleFrame(bar.x(), bar.y(), bar.w(), h, fontSize, bar.align(), lines);
    }

    /**
     * 实际生效的预设:media-right 页若流式块里没有任何媒体块,右栏无物可放,
     * 强行分栏只会把全部内容挤进窄左栏 —— 确定性降级为 standard 全宽排版。
     */
    private static String effectivePresetName(Stage.Scene scene, List<Block> flowBlocks) {
        if ("media-right".equals(scene.preset())
                && flowBlocks.stream().noneMatch(b -> MEDIA_TYPES.contains(b.type()))) {
            return "standard";
        }
        return scene.preset();
    }

    private static List<Block> blocksOf(Stage.Scene scene) {
        return scene.blocks() == null ? List.of() : scene.blocks();
    }

    private static Split splitPinned(Stage.Scene scene) {
        Map<String, Stage.BlockLayout> layouts = indexLayouts(scene);
        List<Frame> pinned = new ArrayList<>();
        List<Block> flow = new ArrayList<>();
        for (Block block : blocksOf(scene)) {
            Stage.BlockLayout layout = layouts.get(block.id());
            if (isPinned(layout)) pinned.add(pinnedFrame(block, layout));
            else flow.add(block);
        }
        return new Split(layouts, pinned, flow);
    }

    /** 钉住块底边超出页面的像素数(文字类块的高度由内容量出,钉在下方时可能出页) */
    private static long pinnedOverflowPx(Frame frame) {
        return (long) Math.ceil(frame.y() + frame.h() - Theme.CANVAS_HEIGHT);
    }

    public PositionedScene layoutScene(Stage.Scene scene) {
        TitleFrame title = layoutTitle(scene);

        // 交互页 / 视频页没有块,网页或视频占满内容区(用首个区域作帧)
        if (Stage.isBlockless(scene.type())) {
            Presets.Region region = Presets.getPreset(scene.preset()).regions().get(0);
            return new PositionedScene(scene.id(), scene.preset(), 1, "none", title,
                    List.of(new Frame("__" + scene.type() + "__", region.x(), region.y(), region.w(), region.h(), 1, true)));
        }

        Split split = splitPinned(scene);
        Presets.PresetDef preset = Presets.getPreset(effectivePresetName(scene, split.flow()));
        List<List<Block>> assigned = assignBlocks(split.flow(), preset.regions().size());
        boolean pinnedOut = split.pinned().stream().anyMatch(f -> pinnedOverflowPx(f) > 0);

        List<Frame> lastFrames = List.of();
        double lastScale = FONT_SCALES[0];

        for (double scale : FONT_SCALES) {
            List<Frame> frames = new ArrayList<>();
            boolean allFit = true;
            for (int ri = 0; ri < preset.regions().size(); ri++) {
                List<Block> blocks = ri < assigned.size() ? assigned.get(ri) : List.of();
                if (blocks.isEmpty()) continue;
                FlowResult result = flowRegion(blocks, preset.regions().get(ri), scale, split.layouts(), split.pinned());
                frames.addAll(result.frames());
                if (!result.fits()) allFit = false;
            }
            lastFrames = frames;
            lastScale = scale;
            if (allFit) {
                List<Frame> all = new ArrayList<>(frames);
                all.addAll(split.pinned());
                return new PositionedScene(scene.id(), scene.preset(), scale,
                        pinnedOut ? "error" : scale == 1 ? "none" : "shrunk", title, all);
            }
        }

        List<Frame> all = new ArrayList<>(lastFrames);
        all.addAll(split.pinned());
        return new PositionedScene(scene.id(), scene.preset(), lastScale, "error", title, all);
    }

    /**
     * 溢出诊断:供生成管线把"具体超了多少"回喂给 LLM。返回 null 表示未溢出。
     * 消息文本必须与 TS 端逐字符一致(golden 用例锁定)。
     */
    public String describeOverflow(Stage.Scene scene) {
        PositionedScene positioned = layoutScene(scene);
        if (!"error".equals(positioned.overflow())) return null;

        Split split = splitPinned(scene);
        Presets.PresetDef preset = Presets.getPreset(effectivePresetName(scene, split.flow()));
        List<List<Block>> assigned = assignBlocks(split.flow(), preset.regions().size());
        List<String> details = new ArrayList<>();
        for (int ri = 0; ri < preset.regions().size(); ri++) {
            Presets.Region region = preset.regions().get(ri);
            List<Block> blocks = ri < assigned.size() ? assigned.get(ri) : List.of();
            if (blocks.isEmpty()) continue;
            FlowResult result = flowRegion(blocks, region, FONT_SCALES[FONT_SCALES.length - 1],
                    split.layouts(), split.pinned());
            if (!result.fits()) {
                long overPx = (long) Math.ceil(result.usedH() - region.h());
                long overRatio = Math.round((overPx / region.h()) * 100);
                details.add("内容区" + (preset.regions().size() > 1 ? " " + (ri + 1) : "")
                        + "在最小字号下仍超出 " + overPx + "px(约 " + overRatio + "%)");
            }
        }
        for (Frame frame : split.pinned()) {
            long overPx = pinnedOverflowPx(frame);
            if (overPx > 0) {
                details.add("钉住的块 " + frame.blockId() + " 底边超出页面 " + overPx + "px");
            }
        }
        return "页面内容放不下:" + String.join(";", details) + "。";
    }
}
