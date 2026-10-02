package cn.utcy.teaching.blockcoding.application.agent;

import java.util.ArrayList;
import java.util.List;

final class ScriptText {
    private ScriptText() {
    }

    static List<String> lines(String code) {
        return code.strip().lines().map(line -> line.replaceAll("\\s*//.*$", "").strip()).filter(l -> !l.isEmpty()).toList();
    }

    static String firstLine(String code) {
        List<String> lines = lines(code);
        return lines.isEmpty() ? "" : lines.getFirst();
    }

    static boolean sameLines(String a, String b) {
        return lines(a).equals(lines(b));
    }

    /** 模型有时仍会在首行写 // sprite: 标注;角色以参数为准,标注去掉 */
    static String stripSpriteHeader(String code) {
        String firstLine = code.lines().findFirst().orElse("");
        if (firstLine.strip().toLowerCase().startsWith("// sprite:")) {
            int newline = code.indexOf('\n');
            return newline < 0 ? "" : code.substring(newline + 1).strip();
        }
        return code;
    }

    /** 去掉空行后的文本,以及每一行在原文里的行号(报错的行号要换算回模型自己那段文本) */
    record Lines(String text, List<Integer> originalLineNumbers) {
        static Lines withoutBlank(String code) {
            List<String> kept = new ArrayList<>();
            List<Integer> numbers = new ArrayList<>();
            int lineNumber = 0;
            for (String line : code.split("\n", -1)) {
                lineNumber++;
                if (!line.isBlank()) {
                    kept.add(line);
                    numbers.add(lineNumber);
                }
            }
            return new Lines(String.join("\n", kept), List.copyOf(numbers));
        }

        int original(int compiledLine) {
            return compiledLine >= 1 && compiledLine <= originalLineNumbers.size() ? originalLineNumbers.get(compiledLine - 1) : compiledLine;
        }

        String remap(String message, int compiledLine) {
            int original = original(compiledLine);
            return original == compiledLine ? message : message.replace("第 " + compiledLine + " 行", "第 " + original + " 行");
        }
    }
}
