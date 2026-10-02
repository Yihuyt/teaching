package cn.utcy.teaching.ai.structured;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.ValidationMessage;

import java.util.List;
import java.util.Set;
import java.util.StringJoiner;

/**
 * JSON Schema 校验失败的可读呈现:摊开 networknt 的错误参数,说清"哪个字段多了 / 缺了 / 枚举允许哪些值",
 * 无论读者是回喂的模型还是看 400 文案的前端开发,都能直接改。
 * getType() 即触发的 schema 关键字;required / additionalProperties 的字段名在 getProperty(),
 * enum 的允许值列表在 getArguments()[0](JSON 数组文本)。
 */
public final class SchemaViolations {

    private SchemaViolations() {
    }

    public static List<String> lines(Set<ValidationMessage> violations, int limit, ObjectMapper objectMapper) {
        return violations.stream()
                .limit(limit)
                .map(v -> line(v, objectMapper))
                .toList();
    }

    public static String line(ValidationMessage v, ObjectMapper objectMapper) {
        String location = v.getInstanceLocation() == null ? "" : v.getInstanceLocation().toString();
        String path = location.isEmpty() || "$".equals(location) ? "(根)" : location;

        String detail = "";
        String keyword = v.getType() == null ? "" : v.getType();
        switch (keyword) {
            case "additionalProperties" -> {
                if (v.getProperty() != null) {
                    detail = "(多余字段: \"" + v.getProperty() + "\")";
                }
            }
            case "required" -> {
                if (v.getProperty() != null) {
                    detail = "(缺少字段: \"" + v.getProperty() + "\")";
                }
            }
            case "enum" -> detail = actualValueDetail(v) + enumDetail(v, objectMapper);
            default -> {
            }
        }
        return "- " + path + " " + v.getError() + detail;
    }

    /** 实际写了什么值:不带出来的话,读者不知道该改哪个词 */
    private static String actualValueDetail(ValidationMessage v) {
        JsonNode actual = v.getInstanceNode();
        return actual == null ? "" : "(实际输出: " + actual + ")";
    }

    /** enum 的 arguments[0] 是形如 ["a", "b"] 的 JSON 数组文本,展开成"允许值: a / b" */
    private static String enumDetail(ValidationMessage v, ObjectMapper objectMapper) {
        Object[] arguments = v.getArguments();
        if (arguments == null || arguments.length == 0 || arguments[0] == null) {
            return "";
        }
        String rendered = String.valueOf(arguments[0]);
        try {
            JsonNode values = objectMapper.readTree(rendered);
            if (values.isArray()) {
                StringJoiner joiner = new StringJoiner(" / ");
                values.forEach(n -> joiner.add(n.asText()));
                return "(允许值: " + joiner + ")";
            }
        } catch (Exception ignored) {
            // 参数格式变化时退回原文,不影响主流程
        }
        return "(允许值: " + rendered + ")";
    }
}
