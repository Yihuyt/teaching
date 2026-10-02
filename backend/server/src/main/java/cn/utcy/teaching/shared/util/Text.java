package cn.utcy.teaching.shared.util;

/**
 * 文本封顶,平台内唯一实现。两种语义,按用途选:
 * truncate = 静默封顶(存储列宽、提示词预算),null 原样返回;
 * abbreviate = 可见封顶(日志、界面摘要、回喂模型的原文),截掉的部分以「…」示意,null 视为空串。
 */
public final class Text {

    private Text() {
    }

    public static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max);
    }

    public static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
