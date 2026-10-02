package cn.utcy.teaching.question.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExplorationTraceTest {

    @Test
    void 按迭代序列化思考工具调用与概括结果() {
        ExplorationTrace trace = new ExplorationTrace();
        trace.appendThought("先查反射定律");
        trace.appendToolCall("rag", "{\"query\":\"反射定律\",\"kb_name\":\"光学\"}");
        trace.appendToolResult("rag", "反射角等于入射角 [source-1]");
        trace.appendThought("够了,收尾");
        trace.finish("本轮将围绕反射定律出题。");

        String rendered = trace.render();

        assertThat(rendered).containsSubsequence(
                "### 迭代 1 —— 思考", "先查反射定律",
                "### 迭代 1 —— 工具调用:rag", "\"query\"",
                "### 迭代 1 —— 工具结果(已概括):rag", "反射角等于入射角 [source-1]",
                "### 最终探索前言(同时展示给用户)", "本轮将围绕反射定律出题。");
        assertThat(rendered).doesNotContain("### 迭代 2 —— 思考");
    }

    @Test
    void 空轨迹给出明确标记() {
        assertThat(new ExplorationTrace().render()).contains("无探索轨迹");
    }
}
