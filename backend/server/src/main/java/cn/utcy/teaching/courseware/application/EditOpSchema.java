package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.ai.structured.SchemaRegistry;
import cn.utcy.teaching.ai.structured.SchemaViolations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编辑操作的形状契约:stage-ops.schema.json 的操作定义(必填 / 可选字段、封闭字段集、枚举)加上它引用的块与动作定义,
 * 每种操作编译成一个校验器。教师端点送来的每条操作都先过这里再解析成 {@link cn.utcy.teaching.courseware.domain.EditOp}:
 * 可选字段缺省是契约允许的,未知字段与缺必填字段是错误。前端 TS 类型与这份 schema 是同一套字段清单。
 */
@Component
public class EditOpSchema {

    private static final String OPS_RESOURCE = "courseware/schemas/stage-ops.schema.json";
    private static final int MAX_LINES = 12;

    private final ObjectMapper objectMapper;
    private final JsonNode definitions;
    private final Map<String, JsonSchema> validators = new LinkedHashMap<>();

    public EditOpSchema(@Qualifier("coursewareSchemas") SchemaRegistry schemas, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        ObjectNode merged = objectMapper.createObjectNode();
        JsonNode ops = loadOpDefinitions(objectMapper);
        merged.setAll((ObjectNode) ops);
        merged.setAll((ObjectNode) schemas.rawSchema("scene-blocks").path("definitions"));
        merged.setAll((ObjectNode) schemas.rawSchema("speech").path("definitions"));
        this.definitions = merged;

        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
        Iterator<String> names = ops.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode opConst = ops.path(name).path("properties").path("op").path("const");
            if (!opConst.isTextual()) {
                continue;
            }
            ObjectNode root = objectMapper.createObjectNode();
            root.put("$schema", "http://json-schema.org/draft-07/schema#");
            root.put("$ref", "#/definitions/" + name);
            root.set("definitions", merged);
            validators.put(opConst.asText(), factory.getSchema(root));
        }
    }

    private static JsonNode loadOpDefinitions(ObjectMapper objectMapper) {
        try (InputStream in = EditOpSchema.class.getClassLoader().getResourceAsStream(OPS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("JSON Schema 资源不存在: " + OPS_RESOURCE);
            }
            return objectMapper.readTree(in).path("definitions");
        } catch (IOException exception) {
            throw new IllegalStateException("JSON Schema 读取失败: " + OPS_RESOURCE, exception);
        }
    }

    public JsonNode definitions() {
        return definitions;
    }

    public static String definitionName(String opName) {
        StringBuilder sb = new StringBuilder();
        for (String part : opName.split("_")) {
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.append("Op").toString();
    }

    public List<String> violations(JsonNode op) {
        String opName = op.path("op").asText("");
        JsonSchema schema = validators.get(opName);
        if (schema == null) {
            return List.of(opName.isEmpty()
                    ? "缺少 op 字段(可用:" + String.join(" / ", validators.keySet()) + ")"
                    : "未知操作 \"" + opName + "\"(可用:" + String.join(" / ", validators.keySet()) + ")");
        }
        Set<ValidationMessage> messages = schema.validate(op);
        return SchemaViolations.lines(messages, MAX_LINES, objectMapper);
    }
}
