package cn.utcy.teaching.ai.llm;

/**
 * 字符启发式 token 估算(不依赖分词器):CJK 字符按 1 token,其余按 4 字符 1 token,
 * 至少 1。供 chat 循环的上下文窗口护栏与历史预算使用——两处必须同一估算器。
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static int count(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                cjk++;
            } else {
                other++;
            }
        }
        return Math.max(1, cjk + (other + 3) / 4);
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
                || (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF00 && cp <= 0xFFEF);
    }
}
