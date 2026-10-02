package cn.utcy.teaching.courseware.infrastructure;

/**
 * 从流式输出的 JSON 文本中增量提取首个 "text" 字符串字段的内容。
 * 模型输出形如 {"text":"...","actions":[...]},完整 JSON 到齐前就把
 * text 的已到达部分解码外送(处理转义与 \\uXXXX),供前端边生成边显示。
 * 只做尽力而为的预览通道——最终以完整 JSON 的校验结果为准。
 */
public final class TextFieldStreamer {

    private enum State { SEEKING, IN_STRING, DONE }

    private static final String KEY = "\"text\"";

    private State state = State.SEEKING;
    private final StringBuilder window = new StringBuilder();
    private boolean afterKey = false;
    private boolean escaping = false;
    private final StringBuilder unicode = new StringBuilder();
    private boolean inUnicode = false;

    public String feed(String rawDelta) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < rawDelta.length(); i++) {
            char c = rawDelta.charAt(i);
            switch (state) {
                case SEEKING -> seek(c);
                case IN_STRING -> decode(c, out);
                case DONE -> {
                    return out.toString();
                }
            }
        }
        return out.toString();
    }

    private void seek(char c) {
        if (!afterKey) {
            window.append(c);
            if (window.length() > KEY.length()) {
                window.delete(0, window.length() - KEY.length());
            }
            if (window.length() == KEY.length() && window.toString().equals(KEY)) {
                afterKey = true;
            }
            return;
        }
        if (c == '"') {
            state = State.IN_STRING;
        } else if (c != ':' && !Character.isWhitespace(c)) {
            // "text" 后不是冒号/引号(例如出现在别的字符串里),重新找键
            afterKey = false;
            window.setLength(0);
        }
    }

    private void decode(char c, StringBuilder out) {
        if (inUnicode) {
            unicode.append(c);
            if (unicode.length() == 4) {
                try {
                    out.append((char) Integer.parseInt(unicode.toString(), 16));
                } catch (NumberFormatException ignored) {
                    // 非法 \\u 序列丢弃——预览通道不因此中断
                }
                unicode.setLength(0);
                inUnicode = false;
            }
            return;
        }
        if (escaping) {
            escaping = false;
            switch (c) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> { // 回车不进预览
                }
                case 'u' -> inUnicode = true;
                default -> out.append(c); // \" \\ \/ 及其它按字面
            }
            return;
        }
        if (c == '\\') {
            escaping = true;
        } else if (c == '"') {
            state = State.DONE;
        } else {
            out.append(c);
        }
    }
}
