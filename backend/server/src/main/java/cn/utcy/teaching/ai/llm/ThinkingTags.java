package cn.utcy.teaching.ai.llm;

import java.util.regex.Pattern;

/**
 * 剥离模型正文里内联的 &lt;think&gt;…&lt;/think&gt; 推理段:
 * 先去闭合块,再去未闭合的开标签到末尾,最后去孤立的闭标签。
 */
public final class ThinkingTags {

    private static final Pattern CLOSED = Pattern.compile(
            "`?<\\s*think(?:ing)?\\b[^>]*>`?.*?`?<\\s*/\\s*think(?:ing)?\\s*>`?",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern UNCLOSED = Pattern.compile(
            "`?<\\s*think(?:ing)?\\b[^>]*>`?.*$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern STRAY_CLOSE = Pattern.compile(
            "`?<\\s*/\\s*think(?:ing)?\\s*>`?", Pattern.CASE_INSENSITIVE);

    private ThinkingTags() {
    }

    public static String strip(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String cleaned = CLOSED.matcher(text).replaceAll("");
        cleaned = UNCLOSED.matcher(cleaned).replaceAll("");
        cleaned = STRAY_CLOSE.matcher(cleaned).replaceAll("");
        return cleaned.strip();
    }
}
