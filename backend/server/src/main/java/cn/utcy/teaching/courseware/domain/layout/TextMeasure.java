package cn.utcy.teaching.courseware.domain.layout;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 文本测量 —— 与 frontend/src/features/courseware/layout/measure.ts 逐行对应。
 * 目标是与 TS 端产生比特一致的 double 运算结果,任何"顺手优化"都可能破坏金标准比对。
 */
public final class TextMeasure {

    private TextMeasure() {
    }

    private static final Set<Integer> NARROW_ASCII = toCodePointSet("iljftrI.,;:'\"!|()[]{}");
    private static final Set<Integer> WIDE_ASCII = toCodePointSet("mwMW@%&");

    /**
     * CJK 断行时禁止出现在行首的标点(跟随前一字符)。
     * 注意:TS 源里这串混用了半角 , ; : ! ? ) % 与全角 。、》】」』”’…· ——
     * 必须按码点原样镜像,不能"纠正"为全角。
     */
    static final Set<Integer> CLOSING_PUNCT =
            toCodePointSet("。,、;:!?)》】」』”’%…·");

    private static Set<Integer> toCodePointSet(String chars) {
        return Set.copyOf(chars.codePoints().boxed().toList());
    }

    private static boolean isCjk(int code) {
        return (code >= 0x2e80 && code <= 0x9fff)
                || (code >= 0x3000 && code <= 0x303f)
                || (code >= 0xf900 && code <= 0xfaff)
                || (code >= 0xff00 && code <= 0xff60)
                || (code >= 0x20000 && code <= 0x2ffff);
    }

    /** 单字符(码点)宽度(em)。判定顺序与 TS 一致:CJK/闭合标点 → 空格 → 窄 → 宽 → 数字 → 大写 → 其余 ASCII */
    public static double charWidthEm(int code) {
        if (isCjk(code) || CLOSING_PUNCT.contains(code)) return 1.0;
        if (code == ' ') return 0.3;
        if (NARROW_ASCII.contains(code)) return 0.35;
        if (WIDE_ASCII.contains(code)) return 0.85;
        if (code >= 0x30 && code <= 0x39) return 0.6;
        if (code >= 0x41 && code <= 0x5a) return 0.68;
        if (code < 0x80) return 0.52;
        return 0.6;
    }

    public static double textWidthEm(String text) {
        double w = 0;
        for (int i = 0; i < text.length(); i = text.offsetByCodePoints(i, 1)) {
            w += charWidthEm(text.codePointAt(i));
        }
        return w;
    }

    private static final Pattern INLINE_LATEX = Pattern.compile("\\$([^$]+)\\$");

    public static String stripInline(String text) {
        return INLINE_LATEX.matcher(text.replace("**", "")).replaceAll("$1");
    }

    /** atomic = 整体不可拆分(西文单词/数字串) */
    private record Token(String text, double widthEm, boolean atomic) {
    }

    private static boolean isWordChar(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9')
                || cp == '_' || cp == '@' || cp == '.' || cp == '-' || cp == '/';
    }

    private static boolean isWordStart(int cp) {
        return (cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z') || (cp >= '0' && cp <= '9');
    }

    private static List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            if (isWordStart(cp)) {
                // 与 TS 正则 ^[A-Za-z0-9_@.\-/]+ 等价:自当前位置贪婪吃整个西文单词
                int end = i;
                while (end < text.length() && isWordChar(text.codePointAt(end))) {
                    end = text.offsetByCodePoints(end, 1);
                }
                String word = text.substring(i, end);
                tokens.add(new Token(word, textWidthEm(word), true));
                i = end;
            } else {
                String ch = text.substring(i, text.offsetByCodePoints(i, 1));
                tokens.add(new Token(ch, charWidthEm(cp), false));
                i = text.offsetByCodePoints(i, 1);
            }
        }
        return tokens;
    }

    private static List<Token> forceSplit(Token token, double maxEm) {
        List<Token> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        double currentW = 0;
        String text = token.text();
        for (int i = 0; i < text.length(); i = text.offsetByCodePoints(i, 1)) {
            int cp = text.codePointAt(i);
            double w = charWidthEm(cp);
            if (currentW + w > maxEm && current.length() > 0) {
                parts.add(new Token(current.toString(), currentW, true));
                current.setLength(0);
                currentW = 0;
            }
            current.appendCodePoint(cp);
            currentW += w;
        }
        if (current.length() > 0) parts.add(new Token(current.toString(), currentW, true));
        return parts;
    }

    /** JS String.prototype.trim 的空白集合(含 NBSP/BOM/全角空格,与 Java strip 不同) */
    private static boolean isJsWhitespace(int cp) {
        return cp == 0x09 || cp == 0x0a || cp == 0x0b || cp == 0x0c || cp == 0x0d
                || cp == 0x20 || cp == 0xa0 || cp == 0x1680
                || (cp >= 0x2000 && cp <= 0x200a)
                || cp == 0x2028 || cp == 0x2029 || cp == 0x202f || cp == 0x205f
                || cp == 0x3000 || cp == 0xfeff;
    }

    private static String jsTrim(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isJsWhitespace(s.codePointAt(start))) {
            start = s.offsetByCodePoints(start, 1);
        }
        while (end > start) {
            int prev = s.offsetByCodePoints(end, -1);
            if (!isJsWhitespace(s.codePointAt(prev))) break;
            end = prev;
        }
        return s.substring(start, end);
    }

    /**
     * 断行:CJK 逐字可断,西文单词整体断行,超宽单词按字符硬拆;
     * 行首禁闭合标点(跟随上一行,允许轻微超宽);行首空白丢弃。
     */
    public static List<String> wrapText(String text, double fontSizePx, double maxWidthPx) {
        String measured = stripInline(text);
        if (jsTrim(measured).isEmpty()) return List.of();
        double maxEm = Math.max(1, maxWidthPx / fontSizePx);

        List<Token> tokens = new ArrayList<>();
        for (Token t : tokenize(measured)) {
            if (t.atomic() && t.widthEm() > maxEm) tokens.addAll(forceSplit(t, maxEm));
            else tokens.add(t);
        }

        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        double lineW = 0;

        for (Token token : tokens) {
            boolean isSpace = jsTrim(token.text()).isEmpty();
            if (lineW + token.widthEm() <= maxEm) {
                if (line.length() == 0 && isSpace) continue;
                line.append(token.text());
                lineW += token.widthEm();
                continue;
            }
            // 放不下:闭合标点强行跟随本行,其余换行。
            // TS 判据是 UTF-16 长度 === 1,闭合标点全在 BMP,length()==1 语义等价
            if (!isSpace && token.text().length() == 1
                    && CLOSING_PUNCT.contains((int) token.text().charAt(0))) {
                line.append(token.text());
                lineW += token.widthEm();
                continue;
            }
            if (line.length() > 0) lines.add(line.toString());
            line = new StringBuilder(isSpace ? "" : token.text());
            lineW = isSpace ? 0 : token.widthEm();
        }
        if (line.length() > 0) lines.add(line.toString());
        return !lines.isEmpty() ? lines : List.of(jsTrim(measured));
    }

    public static int countLines(String text, double fontSizePx, double maxWidthPx) {
        return wrapText(text, fontSizePx, maxWidthPx).size();
    }

    public static double textHeight(String text, double fontSizePx, double maxWidthPx, double lineHeight) {
        return countLines(text, fontSizePx, maxWidthPx) * fontSizePx * lineHeight;
    }
}
