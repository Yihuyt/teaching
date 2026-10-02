package cn.utcy.teaching.blockcoding.engine;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * scratchblocks 解析树的 Java 模型。由 SbParser 从 JS 侧序列化的 JSON 物化；
 * 字段面与 vendored model.js 一致(Glow 在物化时直接解包)。
 */
public sealed interface SbNode {
    record Script(List<SbNode> blocks) implements SbNode {
    }

    /** lineNo 是原文行号(1 起);unclosed = C 形积木没有 end;stray = 不在 C 形积木体内的 end / else */
    record Block(Info info, List<SbNode> children, Comment comment, int lineNo, boolean unclosed, String stray)
            implements SbNode {
        public boolean isHat() {
            return info.shape() != null && info.shape().contains("hat");
        }

        public boolean isDefine() {
            return "define-hat".equals(info.shape());
        }

        public boolean isValueShape() {
            return "reporter".equals(info.shape()) || "boolean".equals(info.shape()) || "ring".equals(info.shape());
        }

        public List<SbNode> slots() {
            List<SbNode> out = new java.util.ArrayList<>();
            for (SbNode child : children) {
                if (child instanceof Input || child instanceof Block) {
                    out.add(child);
                }
            }
            return out;
        }

        public List<Script> scripts() {
            List<Script> out = new java.util.ArrayList<>();
            for (SbNode child : children) {
                if (child instanceof Script script) {
                    out.add(script);
                }
            }
            return out;
        }

        public List<String> labels() {
            List<String> out = new java.util.ArrayList<>();
            for (SbNode child : children) {
                if (child instanceof Label label) {
                    out.add(label.value());
                }
            }
            return out;
        }
    }

    /** value 为字面量文本(嵌套积木是父 Block 的 child，不在 Input 里) */
    record Input(String shape, String value, String menu) implements SbNode {
    }

    record Label(String value) implements SbNode {
    }

    record Icon(String name) implements SbNode {
    }

    record Comment(String value, boolean hasBlock) implements SbNode {
    }

    record Info(
            String id,
            String shape,
            String category,
            boolean categoryIsDefault,
            String selector,
            String argument,
            Map<String, JsonNode> raw
    ) {
        public boolean isObsolete() {
            return "obsolete".equals(category) && categoryIsDefault;
        }
    }

    // ---- 物化 ---------------------------------------------------------------

    static List<Script> scriptsFrom(JsonNode root) {
        List<Script> scripts = new ArrayList<>();
        for (JsonNode script : root.path("scripts")) {
            scripts.add((Script) materialize(script));
        }
        return scripts;
    }

    private static SbNode materialize(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String type = node.path("type").asText();
        return switch (type) {
            case "glow" -> materialize(node.path("child")); // Glow 是包装层，直接解包
            case "script" -> {
                List<SbNode> blocks = new ArrayList<>();
                for (JsonNode child : node.path("blocks")) {
                    SbNode block = materialize(child);
                    if (block != null) {
                        blocks.add(block);
                    }
                }
                yield new Script(blocks);
            }
            case "block" -> {
                JsonNode info = node.path("info");
                Map<String, JsonNode> raw = new LinkedHashMap<>();
                info.properties().forEach(entry -> raw.put(entry.getKey(), entry.getValue()));
                List<SbNode> children = new ArrayList<>();
                for (JsonNode child : node.path("children")) {
                    SbNode c = materialize(child);
                    if (c != null) {
                        children.add(c);
                    }
                }
                Comment comment = node.path("comment").isObject()
                        ? (Comment) materialize(node.get("comment"))
                        : null;
                yield new Block(new Info(
                        info.path("id").asText(null),
                        info.path("shape").asText(null),
                        info.path("category").asText(null),
                        info.path("categoryIsDefault").asBoolean(false),
                        info.path("selector").asText(null),
                        info.path("argument").asText(null),
                        raw), children, comment,
                        node.path("lineNo").asInt(0), node.path("unclosed").asBoolean(false),
                        node.path("stray").isTextual() ? node.get("stray").asText() : null);
            }
            case "input" -> new Input(
                    node.path("shape").asText(null),
                    node.path("value").isTextual() ? node.get("value").asText() : null,
                    node.path("menu").isTextual() ? node.get("menu").asText() : null);
            case "label" -> new Label(node.path("value").asText(""));
            case "icon" -> new Icon(node.path("name").asText(""));
            case "comment" -> new Comment(node.path("value").asText(""),
                    node.path("hasBlock").asBoolean(false));
            default -> null;
        };
    }
}
