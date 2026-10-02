package cn.utcy.teaching.courseware.infrastructure.pptx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行内语法跨语言金样:与 frontend/tests/unit/courseware/inline.test.ts 共同断言
 * inline-golden/cases.json,锁定 Java InlineMarkup 与 TS parseInline 完全一致。
 */
class InlineMarkupGoldenTest {

    @Test
    @DisplayName("全部金样用例与 TS parseInline 输出一致")
    void matchesGoldenCases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/inline-golden/cases.json")) {
            root = mapper.readTree(in);
        }
        assertThat(root.path("cases").size()).isGreaterThanOrEqualTo(15);

        for (JsonNode c : root.path("cases")) {
            String input = c.path("input").asText();
            List<InlineMarkup.Segment> expected = new ArrayList<>();
            for (JsonNode seg : c.path("segments")) {
                expected.add(new InlineMarkup.Segment(
                        seg.path("kind").asText(), seg.path("text").asText()));
            }
            assertThat(InlineMarkup.parse(input))
                    .as("input: %s", input)
                    .containsExactlyElementsOf(expected);
        }
    }

    @Test
    @DisplayName("strip 去标记保留公式体")
    void stripKeepsContent() {
        assertThat(InlineMarkup.strip("**加粗** 与 $F=ma$")).isEqualTo("加粗 与 F=ma");
    }
}
