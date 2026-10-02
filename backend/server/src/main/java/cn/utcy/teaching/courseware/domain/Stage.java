package cn.utcy.teaching.courseware.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 课件文档模型 —— 与前端 courseware/dsl 对齐。
 * 身份与时间戳归数据库行(courseware 表)持有,文档只承载内容本身;
 * 可选字段落库时以 null 形式保留(平台未配置 NON_NULL);模型输出侧的缺省语义见 LlmOutputMappers。
 */
public record Stage(
        String title,
        String theme,
        List<Scene> scenes
) {

    public record Scene(
            String id,
            String type,
            String title,
            String preset,
            /* 本页要讲什么(2~3 句):生成与重生成的内容依据 */
            String summary,
            List<Block> blocks,
            List<SpeechSegment> speech,
            /* 教师手工排版覆盖(按顶层块 id 引用;null / 空即全自动排版) */
            List<BlockLayout> layouts,
            Interactive interactive,
            /* 只有 video 页有:整页就是一个视频 */
            Video video
    ) {
        public Scene(String id, String type, String title, String preset, String summary, List<Block> blocks,
                     List<SpeechSegment> speech, List<BlockLayout> layouts, Interactive interactive) {
            this(id, type, title, preset, summary, blocks, speech, layouts, interactive, null);
        }

        public List<BlockLayout> layoutsOrEmpty() {
            return layouts == null ? List.of() : layouts;
        }
    }

    /**
     * 钉住帧:教师在画布上定下的位置与宽度(1280×720 逻辑像素)。
     * 高度只对 image / chart 这类"高度不由内容决定"的块存在(其余块为 null,由内容量出)。
     */
    public record PinFrame(double x, double y, double w, Double h) {
    }

    /**
     * 一个顶层块的排版覆盖:钉住帧与/或字号档(small / large / xlarge),缺的那项仍由引擎决定。
     * 只能引用页面顶层块;columns 只能改字号档、不能钉住。被钉住的块不参与流式排版与页级缩字。
     */
    public record BlockLayout(String blockId, PinFrame frame, String size) {
    }

    /**
     * 交互仿真页内容:自包含 HTML + 生成它的组件规格(widgetType 四选一与松散的 widgetOutline),
     * 规格随页落库——重生成时仍能路由到专属提示词,不退化为通用模板。
     */
    public record Interactive(String html, String widgetType, JsonNode widgetOutline) {
    }

    public record Video(String src) {
    }

    public record SpeechSegment(String text, List<Action> actions, String audioPath) {
    }

    /** 页面类型与布局预设的合法值(与 dsl 常量一致) */
    public static final List<String> SCENE_TYPES = List.of("content", "quiz", "interactive", "video");

    /** 没有内容块的页面类型:整页是一份网页或一个视频,讲稿只允许 pause,没有排版覆盖 */
    public static boolean isBlockless(String sceneType) {
        return "interactive".equals(sceneType) || "video".equals(sceneType);
    }

    public static final List<String> WIDGET_TYPES = List.of(
            "simulation", "diagram", "game", "visualization3d");

    public static final List<String> PRESET_NAMES = List.of(
            "title-cover", "standard", "two-column", "media-right", "quiz", "section-divider");

    /** 块字号档(排版覆盖里的 size;倍率见 layout.Theme) */
    public static final List<String> BLOCK_SIZES = List.of("small", "large", "xlarge");

    /** 钉住时高度由教师决定(而非内容量出)的块类型 */
    public static final List<String> FREE_HEIGHT_TYPES = List.of("image", "chart");

    /** 钉住帧的最小宽 / 高(逻辑像素) */
    public static final double MIN_PIN_WIDTH = 120;
    public static final double MIN_PIN_HEIGHT = 60;
}
