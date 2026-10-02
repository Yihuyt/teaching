package cn.utcy.teaching.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptLoaderTest {

    private final PromptLoader loader = new PromptLoader(new ObjectMapper());

    @Test
    @DisplayName("snippet 内联展开,不残留占位符")
    void expandsSnippetInline() {
        PromptLoader.Prompt prompt = loader.build("courseware/prompts", "scene-speech", Map.of("sceneTitle", "引言"));
        assertThat(prompt.system()).contains("输出格式要求");
        assertThat(prompt.system()).contains("直接输出纯 JSON");
        assertThat(prompt.system()).doesNotContain("{{snippet:");
    }

    @Test
    @DisplayName("{{#if}} 条件块:变量为真保留并插值,为假整段移除")
    void conditionalBlocksFollowTruthiness() {
        PromptLoader.Prompt withNext = loader.build("courseware/prompts", "scene-speech",
                Map.of("sceneTitle", "引言", "nextSceneTitle", "牛顿第二定律"));
        assertThat(withNext.user()).contains("# 下一页标题(结尾衔接用)");
        assertThat(withNext.user()).contains("牛顿第二定律");

        PromptLoader.Prompt withoutNext = loader.build("courseware/prompts", "scene-speech",
                Map.of("sceneTitle", "引言"));
        assertThat(withoutNext.user()).doesNotContain("下一页标题");
        assertThat(withoutNext.user()).doesNotContain("{{#if");
        assertThat(withoutNext.user()).doesNotContain("{{/if}}");
        assertThat(loader.build("courseware/prompts", "scene-speech", Map.of("isInteractive", true)).system())
                .contains("本页是交互仿真页");
        assertThat(withoutNext.system()).doesNotContain("本页是交互仿真页");
    }

    @Test
    @DisplayName("变量插值:字符串原样、对象转缩进 JSON、缺失变量原样保留")
    void interpolatesVariables() {
        PromptLoader.Prompt prompt = loader.build("courseware/prompts", "scene-speech", Map.of(
                "sceneTitle", "引言",
                "schemaJson", Map.of("type", "object")));
        assertThat(prompt.user()).contains("引言");
        assertThat(prompt.system()).doesNotContain("{{schemaJson}}");
        assertThat(prompt.system()).contains("\"type\"");
        assertThat(prompt.system()).contains("\"object\"");

        PromptLoader.Prompt missingVar = loader.build("courseware/prompts", "scene-speech", Map.of());
        assertThat(missingVar.user()).contains("{{sceneTitle}}");
    }

    @Test
    @DisplayName("缺失 snippet 立即抛错")
    void missingSnippetThrows() {
        assertThatThrownBy(() -> loader.build("courseware/prompts", "bad-snippet", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Prompt snippet 不存在: courseware/prompts/snippets/no-such-snippet.md");
    }
}
