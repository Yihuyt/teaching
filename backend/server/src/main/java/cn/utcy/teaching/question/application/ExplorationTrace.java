package cn.utcy.teaching.question.application;

import java.util.ArrayList;
import java.util.List;

/**
 * 探索轨迹:把探索阶段的思考、
 * 工具调用参数、概括后的工具结果、收尾前言按迭代序列化为 markdown,
 * 作为规划与逐题阶段的唯一实质上下文。
 */
public final class ExplorationTrace {

    private final List<String> blocks = new ArrayList<>();
    private final StringBuilder thought = new StringBuilder();
    private int iteration = 1;
    /** 上一条记录是否是工具结果:之后再来的文字属于新一轮迭代 */
    private boolean afterToolResult;

    public synchronized void appendThought(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (afterToolResult) {
            iteration++;
            afterToolResult = false;
        }
        thought.append(delta);
    }

    public synchronized void appendToolCall(String tool, String argumentsJson) {
        flushThought();
        blocks.add("### 迭代 " + iteration + " —— 工具调用:" + tool + "\n```json\n"
                + (argumentsJson == null ? "{}" : argumentsJson.strip()) + "\n```");
    }

    public synchronized void appendToolResult(String tool, String summarized) {
        blocks.add("### 迭代 " + iteration + " —— 工具结果(已概括):" + tool + "\n"
                + (summarized == null ? "" : summarized.strip()));
        afterToolResult = true;
    }

    public synchronized void finish(String finalText) {
        thought.setLength(0);
        if (finalText != null && !finalText.isBlank()) {
            blocks.add("### 最终探索前言(同时展示给用户)\n" + finalText.strip());
        }
    }

    private void flushThought() {
        if (thought.toString().isBlank()) {
            thought.setLength(0);
            return;
        }
        blocks.add("### 迭代 " + iteration + " —— 思考\n" + thought.toString().strip());
        thought.setLength(0);
    }

    public synchronized String render() {
        if (blocks.isEmpty() && thought.toString().isBlank()) {
            return "(无探索轨迹——探索阶段未产生任何内容;请仅依据出题要求出题)";
        }
        List<String> all = new ArrayList<>(blocks);
        if (!thought.toString().isBlank()) {
            all.add("### 迭代 " + iteration + " —— 思考\n" + thought.toString().strip());
        }
        return String.join("\n\n", all);
    }
}
