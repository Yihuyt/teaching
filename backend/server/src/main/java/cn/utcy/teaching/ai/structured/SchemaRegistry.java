package cn.utcy.teaching.ai.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 classpath:{resourceDir}/{key}.schema.json 装载 draft-07 JSON Schema。
 * 同时保留原始 JsonNode(写入 prompt / response_format)与编译后的校验器。
 * 各业务模块自声明 bean 并给出自己的资源目录与 key 清单;
 * 资源缺失说明构建产物不完整,启动即炸。
 */
public class SchemaRegistry {

    private final Map<String, JsonNode> rawSchemas = new LinkedHashMap<>();
    private final Map<String, JsonSchema> validators = new LinkedHashMap<>();

    public SchemaRegistry(ObjectMapper objectMapper, String resourceDir, List<String> keys) {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        for (String key : keys) {
            String path = resourceDir + "/" + key + ".schema.json";
            try (InputStream in = SchemaRegistry.class.getClassLoader().getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("JSON Schema 资源不存在: " + path);
                }
                JsonNode node = objectMapper.readTree(in);
                rawSchemas.put(key, node);
                validators.put(key, factory.getSchema(node));
            } catch (IOException e) {
                throw new IllegalStateException("JSON Schema 读取失败: " + path, e);
            }
        }
    }

    public JsonNode rawSchema(String key) {
        JsonNode node = rawSchemas.get(key);
        if (node == null) {
            throw new IllegalArgumentException("未注册的 schema: " + key);
        }
        return node;
    }

    public JsonSchema validator(String key) {
        JsonSchema schema = validators.get(key);
        if (schema == null) {
            throw new IllegalArgumentException("未注册的 schema: " + key);
        }
        return schema;
    }
}
