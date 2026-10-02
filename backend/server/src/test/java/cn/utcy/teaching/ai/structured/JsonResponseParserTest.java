package cn.utcy.teaching.ai.structured;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonResponseParserTest {

    private static JsonNode tree(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 直接解析合法JSON() {
        JsonNode node = JsonResponseParser.parse("{\"key\": \"value\"}");
        assertThat(node.path("key").asText()).isEqualTo("value");
    }

    @Test
    void 剥markdown代码块() {
        JsonNode node = JsonResponseParser.parse("```json\n{\"key\": \"value\"}\n```");
        assertThat(node.path("key").asText()).isEqualTo("value");
    }

    @Test
    void 清理think前缀后解析() {
        JsonNode node = JsonResponseParser.parse(
                "<think>我先想一想,{\"draft\": 1} 这样如何</think>{\"key\": \"value\"}");
        assertThat(node.path("key").asText()).isEqualTo("value");
    }

    @Test
    void 散文包裹时取最长可解码值_不被小片段干扰() {
        JsonNode node = JsonResponseParser.parse(
                "参考示例 {\"id\": 1},以下是完整结果:\n"
                        + "{\"items\": [{\"title\": \"顶点坐标\"}, {\"title\": \"对称轴\"}]}\n谢谢。");
        assertThat(node.path("items")).hasSize(2);
    }

    @Test
    void 残缺JSON经修复库解析_尾逗号与缺引号() {
        JsonNode node = JsonResponseParser.parse("{\"items\": [{\"title\": \"顶点坐标\"},]}");
        assertThat(node.path("items")).hasSize(1);
    }

    @Test
    void 截断的JSON被修复补全() {
        JsonNode node = JsonResponseParser.parse("{\"items\": [{\"title\": \"顶点坐标\"");
        assertThat(node).isNotNull();
        assertThat(node.path("items").get(0).path("title").asText()).isEqualTo("顶点坐标");
    }

    @Test
    void 纯散文返回null() {
        assertThat(JsonResponseParser.parse("对不起,我无法完成这个任务。")).isNull();
    }

    @Test
    void 空串返回null() {
        assertThat(JsonResponseParser.parse("")).isNull();
        assertThat(JsonResponseParser.parse(null)).isNull();
    }

    @Test
    void 只有结束标记没有开始标记的思考前缀也能剥掉() {
        assertThat(JsonResponseParser.parse("思考中…</think>{\"a\":1}")).isEqualTo(tree("{\"a\":1}"));
    }

    @Test
    void 散文里夹着的对象_字符串内的括号不干扰截取() {
        assertThat(JsonResponseParser.parse("好的,结果如下:{\"a\":{\"b\":\"}\"}},请查收"))
                .isEqualTo(tree("{\"a\":{\"b\":\"}\"}}"));
    }

    @Test
    void 尾逗号被修复() {
        assertThat(JsonResponseParser.parse("{\"a\":[1,2,],}")).isEqualTo(tree("{\"a\":[1,2]}"));
    }

    @Test
    void JSON_null_视为没有可用值() {
        assertThat(JsonResponseParser.parse("null")).isNull();
        assertThat(JsonResponseParser.parse("```json\nnull\n```")).isNull();
    }
}
