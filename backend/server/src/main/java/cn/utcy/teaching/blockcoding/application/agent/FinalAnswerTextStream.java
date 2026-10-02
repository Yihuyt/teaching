package cn.utcy.teaching.blockcoding.application.agent;

/**
 * 从 final_answer 工具参数的流式 JSON 片段里边到边抠出 text 的值:模型把 {"text": "…"} 一截一截发来,
 * 不等 JSON 闭合就把已到的字符串内容(转义已还原)按增量交出去,学生看到的是逐字出现的回复。
 * 只认第一个 "text" 键;字符串结束后忽略其余内容。
 */
final class FinalAnswerTextStream {
    private enum State { KEY, COLON, QUOTE, STRING, DONE }

    private final StringBuilder pending = new StringBuilder();
    private State state = State.KEY;

    String feed(String fragment) {
        if (fragment == null || fragment.isEmpty() || state == State.DONE) {
            return "";
        }
        pending.append(fragment);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < pending.length() && state != State.DONE) {
            switch (state) {
                case KEY -> {
                    int at = pending.indexOf("\"text\"", i);
                    if (at < 0) {
                        i = Math.max(i, pending.length() - 5); // 键可能被拆在两片里,留个尾巴
                        pending.delete(0, i);
                        return out.toString();
                    }
                    i = at + 6;
                    state = State.COLON;
                }
                case COLON -> {
                    while (i < pending.length() && Character.isWhitespace(pending.charAt(i))) {
                        i++;
                    }
                    if (i >= pending.length()) {
                        pending.delete(0, i);
                        return out.toString();
                    }
                    if (pending.charAt(i) != ':') {
                        state = State.KEY; // 不是键,是文本里恰好出现的 "text"
                    } else {
                        i++;
                        state = State.QUOTE;
                    }
                }
                case QUOTE -> {
                    while (i < pending.length() && Character.isWhitespace(pending.charAt(i))) {
                        i++;
                    }
                    if (i >= pending.length()) {
                        pending.delete(0, i);
                        return out.toString();
                    }
                    if (pending.charAt(i) != '"') {
                        state = State.DONE; // 值不是字符串,放弃流式,收尾时用完整 JSON
                    } else {
                        i++;
                        state = State.STRING;
                    }
                }
                case STRING -> {
                    char c = pending.charAt(i);
                    if (c == '"') {
                        state = State.DONE;
                        i++;
                    } else if (c == '\\') {
                        if (i + 1 >= pending.length()) {
                            pending.delete(0, i);
                            return out.toString(); // 转义被拆在两片里,等下一片
                        }
                        char e = pending.charAt(i + 1);
                        if (e == 'u') {
                            if (i + 6 > pending.length()) {
                                pending.delete(0, i);
                                return out.toString();
                            }
                            out.append((char) Integer.parseInt(pending.substring(i + 2, i + 6), 16));
                            i += 6;
                        } else {
                            out.append(switch (e) {
                                case 'n' -> '\n';
                                case 't' -> '\t';
                                case 'r' -> '\r';
                                case 'b' -> '\b';
                                case 'f' -> '\f';
                                default -> e; // \" \\ \/
                            });
                            i += 2;
                        }
                    } else {
                        out.append(c);
                        i++;
                    }
                }
                default -> {
                }
            }
        }
        pending.delete(0, Math.min(i, pending.length()));
        return out.toString();
    }
}
