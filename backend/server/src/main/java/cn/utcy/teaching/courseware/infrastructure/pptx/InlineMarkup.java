package cn.utcy.teaching.courseware.infrastructure.pptx;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行内语法(`**加粗**`、`$latex$`)解析 —— 与 frontend/src/features/courseware/inline.ts 的 parseInline 逐行为对应。
 * 真源是 TS 端(屏幕渲染);两端由 inline-golden/cases.json 金样锁定一致,
 * 改语法必须先改 TS,再更新金样,最后修此处至绿。
 */
public final class InlineMarkup {

    private InlineMarkup() {
    }

    /** kind: text | bold | latex(latex 时 text 为去定界符的公式体) */
    public record Segment(String kind, String text) {
    }

    private static final Pattern LATEX = Pattern.compile("\\$[^$]+\\$");
    private static final Pattern BOLD = Pattern.compile("\\*\\*[^*]+\\*\\*");

    public static List<Segment> parse(String text) {
        List<Segment> segments = new ArrayList<>();
        // 与 TS 一致:先按 $...$ 切,再在文本段里按 **...** 切
        for (String part : splitKeepingMatches(text, LATEX)) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.startsWith("$") && part.endsWith("$") && part.length() > 2) {
                segments.add(new Segment("latex", part.substring(1, part.length() - 1)));
                continue;
            }
            for (String bp : splitKeepingMatches(part, BOLD)) {
                if (bp.isEmpty()) {
                    continue;
                }
                if (bp.startsWith("**") && bp.endsWith("**") && bp.length() > 4) {
                    segments.add(new Segment("bold", bp.substring(2, bp.length() - 2)));
                } else {
                    segments.add(new Segment("text", bp));
                }
            }
        }
        return segments;
    }

    public static String strip(String text) {
        StringBuilder sb = new StringBuilder();
        for (Segment seg : parse(text)) {
            sb.append(seg.text());
        }
        return sb.toString();
    }

    /** 等价于 JS 的 String.split(带捕获组正则):匹配段也保留在结果里 */
    private static List<String> splitKeepingMatches(String text, Pattern pattern) {
        List<String> parts = new ArrayList<>();
        Matcher m = pattern.matcher(text);
        int last = 0;
        while (m.find()) {
            parts.add(text.substring(last, m.start()));
            parts.add(m.group());
            last = m.end();
        }
        parts.add(text.substring(last));
        return parts;
    }
}
