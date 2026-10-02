package cn.utcy.teaching.blockcoding.engine;

import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Diagnostic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 切词前的字符级检查:scratchblocks 的解析器对任何输入都会"猜"出一个形状,括号少一个、写成全角它都不报错,
 * 所以这两类问题要在文本上查,精确到第几个字符。只管三种括号:( ) 圆槽、[ ] 文字与下拉、< > 条件。
 * 规则和解析器一致:[ ] 里是原文(\ 转义,全角也不管),紧跟在一个槽后面、后面又接一个槽的 < 或 > 是比较运算符不是括号。
 */
final class SyntaxCheck {

    private static final String FULL_WIDTH = "（）［］＜＞０１２３４５６７８９";
    private static final String HALF_WIDTH = "()[]<>0123456789";

    private SyntaxCheck() {
    }

    static List<Diagnostic> check(String code) {
        List<Diagnostic> errors = new ArrayList<>();
        String[] lines = code.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Diagnostic error = checkLine(lines[i], i + 1);
            if (error != null) {
                errors.add(error);
            }
        }
        return errors;
    }

    private static Diagnostic checkLine(String line, int lineNo) {
        if (line.stripLeading().startsWith("//")) {
            return null;
        }
        Deque<Integer> opens = new ArrayDeque<>();
        Deque<Character> kinds = new ArrayDeque<>();
        boolean inString = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == ']') {
                    inString = false;
                    kinds.pop();
                    opens.pop();
                }
                continue;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            }
            int half = FULL_WIDTH.indexOf(c);
            if (half >= 0) {
                return new Diagnostic(lineNo, "第 " + lineNo + " 行第 " + (i + 1) + " 个字符是全角的 " + c + ",要用半角的 " + HALF_WIDTH.charAt(half), List.of());
            }
            if (c == '[') {
                inString = true;
                kinds.push('[');
                opens.push(i + 1);
            } else if (c == '(') {
                kinds.push('(');
                opens.push(i + 1);
            } else if (c == '<') {
                if (isComparison(line, i)) {
                    continue;
                }
                kinds.push('<');
                opens.push(i + 1);
            } else if (c == ')' || c == '>') {
                if (c == '>' && isComparison(line, i)) {
                    continue;
                }
                char expected = c == ')' ? '(' : '<';
                if (kinds.isEmpty()) {
                    return new Diagnostic(lineNo, "第 " + lineNo + " 行第 " + (i + 1) + " 个字符:多了一个 " + c + ",前面没有和它配对的 " + expected, List.of());
                }
                if (kinds.peek() != expected) {
                    return new Diagnostic(lineNo, "第 " + lineNo + " 行第 " + (i + 1) + " 个字符:这里的 " + c + " 对不上前面第 " + opens.peek()
                            + " 个字符的 " + kinds.peek() + ";括号要成对嵌套", List.of());
                }
                kinds.pop();
                opens.pop();
            }
        }
        if (inString) {
            return new Diagnostic(lineNo, "第 " + lineNo + " 行第 " + opens.peek() + " 个字符的 [ 没有配对的 ]", List.of());
        }
        if (!kinds.isEmpty()) {
            char open = kinds.peek();
            char close = open == '(' ? ')' : '>';
            return new Diagnostic(lineNo, "第 " + lineNo + " 行第 " + opens.peek() + " 个字符的 " + open + " 少了配对的 " + close, List.of());
        }
        return null;
    }

    /** 前面刚关掉一个槽、后面又开一个槽的 < 或 >,是比较运算符不是括号(如 <(a) > (b)>、when [loudness v] > (10)) */
    private static boolean isComparison(String line, int at) {
        int before = at - 1;
        while (before >= 0 && line.charAt(before) == ' ') {
            before--;
        }
        int after = at + 1;
        while (after < line.length() && line.charAt(after) == ' ') {
            after++;
        }
        boolean closedSlotBefore = before >= 0 && ")]>".indexOf(line.charAt(before)) >= 0;
        boolean opensSlotAfter = after < line.length() && "([<".indexOf(line.charAt(after)) >= 0;
        return closedSlotBefore && opensSlotAfter;
    }
}
