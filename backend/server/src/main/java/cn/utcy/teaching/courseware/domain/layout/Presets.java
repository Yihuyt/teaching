package cn.utcy.teaching.courseware.domain.layout;

import java.util.List;

/**
 * 布局预设 —— 与 frontend/src/features/courseware/layout/presets.ts 逐行对应。
 * 每个预设 = 标题条定义 + 1~2 个内容区矩形,坐标为 1280×720 逻辑像素。
 */
public final class Presets {

    private Presets() {
    }

    public record Region(double x, double y, double w, double h) {
    }

    public record TitleBar(double x, double y, double w, double fontSize, String align) {
    }

    /** titleBar 为 null = 该预设不渲染独立标题条 */
    public record PresetDef(TitleBar titleBar, List<Region> regions) {
    }

    public static PresetDef getPreset(String name) {
        double width = Theme.CANVAS_WIDTH;
        double height = Theme.CANVAS_HEIGHT;
        double margin = 60;
        double contentW = width - margin * 2;

        return switch (name) {
            case "title-cover" -> new PresetDef(
                    new TitleBar(120, 250, width - 240, Theme.FONT_COVER, "center"),
                    List.of(new Region(240, 420, width - 480, 180)));
            case "section-divider" -> new PresetDef(
                    new TitleBar(120, 280, width - 240, 48, "center"),
                    List.of(new Region(240, 410, width - 480, 150)));
            // two-column 的分栏由页面内 columns 块承担,区域框架与 standard 相同
            case "standard", "two-column" -> new PresetDef(
                    new TitleBar(margin, 44, contentW, Theme.FONT_SCENE_TITLE, "left"),
                    List.of(new Region(margin, 150, contentW, height - 150 - 50)));
            case "media-right" -> new PresetDef(
                    new TitleBar(margin, 44, contentW, Theme.FONT_SCENE_TITLE, "left"),
                    List.of(
                            new Region(margin, 150, 620, height - 150 - 50),
                            new Region(margin + 620 + 60, 150, contentW - 620 - 60, height - 150 - 50)));
            case "quiz" -> new PresetDef(
                    new TitleBar(margin, 44, contentW, Theme.FONT_SCENE_TITLE, "left"),
                    List.of(new Region(100, 150, width - 200, height - 150 - 50)));
            default -> throw new IllegalArgumentException("未知布局预设:" + name);
        };
    }
}
