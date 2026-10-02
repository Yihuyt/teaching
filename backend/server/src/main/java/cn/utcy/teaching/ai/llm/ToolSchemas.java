package cn.utcy.teaching.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把仓库里的 JSON Schema 片段交给 LangChain4j 作工具参数定义:schema 文件之间用 {@code #/definitions/} 互相引用,
 * 而工具定义里的子 schema 挂在 {@code $defs} 下,引用要改写成 {@code #/$defs/}。
 */
public final class ToolSchemas {

    private static final String FILE_REF_PREFIX = "#/definitions/";
    public static final String DEFS_REF_PREFIX = "#/$defs/";

    private ToolSchemas() {
    }

    public static JsonRawSchema raw(JsonNode schema) {
        return JsonRawSchema.from(rewriteRefs(schema.deepCopy()).toString());
    }

    public static Map<String, JsonSchemaElement> definitions(Map<String, JsonNode> schemas) {
        Map<String, JsonSchemaElement> definitions = new LinkedHashMap<>();
        schemas.forEach((name, schema) -> definitions.put(name, raw(schema)));
        return definitions;
    }

    private static JsonNode rewriteRefs(JsonNode node) {
        if (node instanceof ObjectNode object) {
            JsonNode ref = object.get("$ref");
            if (ref != null && ref.isTextual() && ref.asText().startsWith(FILE_REF_PREFIX)) {
                object.set("$ref", TextNode.valueOf(DEFS_REF_PREFIX + ref.asText().substring(FILE_REF_PREFIX.length())));
            }
            object.fields().forEachRemaining(entry -> rewriteRefs(entry.getValue()));
        } else if (node instanceof ArrayNode array) {
            array.forEach(ToolSchemas::rewriteRefs);
        }
        return node;
    }
}
