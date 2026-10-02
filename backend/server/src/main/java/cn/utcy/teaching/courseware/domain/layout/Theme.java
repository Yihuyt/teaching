package cn.utcy.teaching.courseware.domain.layout;

/**
 * 主题 token(布局相关子集)—— 与 frontend/src/features/courseware/layout/theme.ts 的 DEFAULT_THEME 逐值对齐。
 * 颜色/字体族与排版数学无关,不在此镜像。
 */
public final class Theme {

    private Theme() {
    }

    public static final double CANVAS_WIDTH = 1280;
    public static final double CANVAS_HEIGHT = 720;

    /** 块字号档 → 倍率(与 dsl/stage.ts 的 BLOCK_SIZE_SCALES 同值) */
    public static double blockSizeScale(String size) {
        if (size == null) return 1;
        return switch (size) {
            case "small" -> 0.85;
            case "large" -> 1.2;
            case "xlarge" -> 1.45;
            default -> throw new IllegalArgumentException("未知字号档: " + size);
        };
    }

    public static final double FONT_COVER = 56;
    public static final double FONT_SCENE_TITLE = 36;
    public static final double FONT_H1 = 44;
    public static final double FONT_H2 = 32;
    public static final double FONT_BODY = 24;
    public static final double FONT_SMALL = 18;
    public static final double FONT_CODE = 18;

    public static final double LINE_HEIGHT = 1.5;
    public static final double CODE_LINE_HEIGHT = 1.6;

    public static final double SPACING_XS = 8;
    public static final double SPACING_SM = 16;
    public static final double SPACING_MD = 24;
    public static final double SPACING_LG = 40;
}
