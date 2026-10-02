package cn.utcy.teaching.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSchemasTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 引用改写为defs并递归到数组与嵌套对象() throws Exception {
        JsonNode schema = mapper.readTree("""
                {"type":"object","properties":{
                  "child":{"$ref":"#/definitions/Child"},
                  "items":{"type":"array","items":{"$ref":"#/definitions/Item"}},
                  "nested":{"type":"object","properties":{"deep":{"$ref":"#/definitions/Deep"}}}
                }}
                """);
        JsonRawSchema raw = ToolSchemas.raw(schema);
        JsonNode rewritten = mapper.readTree(raw.schema());
        assertThat(rewritten.at("/properties/child/$ref").asText()).isEqualTo("#/$defs/Child");
        assertThat(rewritten.at("/properties/items/items/$ref").asText()).isEqualTo("#/$defs/Item");
        assertThat(rewritten.at("/properties/nested/properties/deep/$ref").asText()).isEqualTo("#/$defs/Deep");
    }

    @Test
    void 原schema不被修改() throws Exception {
        JsonNode schema = mapper.readTree("{\"$ref\":\"#/definitions/X\"}");
        ToolSchemas.raw(schema);
        assertThat(schema.get("$ref").asText()).isEqualTo("#/definitions/X");
    }

    @Test
    void definitions保持名称顺序() throws Exception {
        JsonNode a = mapper.readTree("{\"type\":\"string\"}");
        JsonNode b = mapper.readTree("{\"type\":\"integer\"}");
        var defs = ToolSchemas.definitions(new java.util.LinkedHashMap<>(Map.of("A", a)) {{ put("B", b); }});
        assertThat(defs.keySet()).containsExactly("A", "B");
    }
}
