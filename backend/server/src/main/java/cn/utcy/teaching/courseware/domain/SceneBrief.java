package cn.utcy.teaching.courseware.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 一页的生成简报(大纲里的一页,也是单页生成的输入):标题 / 类型 / 预设 / 概要 / 要点(几条短句,内容生成的硬依据;
 * 单页重生成时没有要点,以现有内容为基准),交互页另带组件规格
 * (widgetType 四选一 + 松散的 widgetOutline),讲解页可带本页可用的素材图片 id 清单和一条要 AI 画的配图。
 * 只在生成期存在;大纲本身不落库——页面是唯一真相。
 */
public record SceneBrief(String title, String type, String preset, String summary, List<String> keyPoints,
                        String widgetType, JsonNode widgetOutline, List<String> imageIds, Illustration illustration) {

    public static final int MAX_KEY_POINTS = 8;
    public static final int MAX_KEY_POINT_CHARS = 120;

    /**
     * 本页要 AI 画的一张配图:画面描述 + 宽高比。素材里没有合适的图而这页确实需要一张示意图时,
     * 大纲模型提出、教师确认时可改可删,逐页生成时先画出来再交给内容模型放上页面。
     */
    public record Illustration(String prompt, String aspectRatio) {

        public static final int MAX_PROMPT_CHARS = 800;
        public static final String DEFAULT_ASPECT_RATIO = "16:9";
        public static final List<String> ASPECT_RATIOS = List.of("16:9", "4:3", "1:1", "3:4");

        public static Illustration normalize(Illustration raw) {
            if (raw == null || raw.prompt() == null || raw.prompt().isBlank()) {
                return null;
            }
            String prompt = raw.prompt().strip();
            if (prompt.length() > MAX_PROMPT_CHARS) {
                prompt = prompt.substring(0, MAX_PROMPT_CHARS);
            }
            String ratio = raw.aspectRatio() != null && ASPECT_RATIOS.contains(raw.aspectRatio())
                    ? raw.aspectRatio() : DEFAULT_ASPECT_RATIO;
            return new Illustration(prompt, ratio);
        }
    }

    public static List<String> normalizeKeyPoints(List<String> raw) {
        List<String> out = new java.util.ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String point : raw) {
            if (point == null) {
                continue;
            }
            String trimmed = point.strip();
            if (trimmed.isEmpty() || out.size() >= MAX_KEY_POINTS) {
                continue;
            }
            out.add(trimmed.length() > MAX_KEY_POINT_CHARS ? trimmed.substring(0, MAX_KEY_POINT_CHARS) : trimmed);
        }
        return out;
    }
}
