package cn.utcy.teaching.blockcoding.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 积木表:编辑器自己的积木定义(scratch-blocks 定义 + 工具箱影子块 + pen/music 扩展),由
 * 从编辑器的定义导出为 editor-blocks.json。转换器正反两向都只查这一张表:
 * 每种积木一个模板,模板的每个槽带类型,文本记法里的 scratchblocks id 直接接到 opcode。
 */
@Component
public class BlockTable {
    public enum Shape { HAT, STATEMENT, CAP, REPORTER, BOOLEAN }

    /** 槽的种类:值槽(数字/文字/布尔/颜色/菜单影子块)、子脚本、下拉字段、变量字段(变量/列表/广播)、文字标签、图标 */
    public enum ArgKind { INPUT, SUBSTACK, MENU, VARIABLE, LABEL, IMAGE, FIELD }

    public record Shadow(String type, String field, String value) {
    }

    /** label → value;label 是学生写的词,value 是写进 XML 的值 */
    public record Option(String label, String value) {
    }

    public record Arg(String name, ArgKind kind, boolean booleanInput, Shadow shadow, List<Option> options,
                      boolean dynamicOptions, String variableType, String image) {
        public String resolveOption(String text) {
            if (options == null) {
                return null;
            }
            for (Option option : options) {
                if (option.value().equals(text)) {
                    return option.value();
                }
            }
            for (Option option : options) {
                if (option.label().equalsIgnoreCase(text)) {
                    return option.value();
                }
            }
            return null;
        }

        public List<String> optionLabels() {
            return options == null ? List.of() : options.stream().map(Option::label).toList();
        }
    }

    /**
     * spec 是 scratchblocks 拼写表里的写法(如 "move %1 steps"、"when @greenFlag clicked"),文本记法以它为准;
     * text 是编辑器文案,只在拼写表没有对应条目时(菜单积木等)兜底
     */
    public record Template(String opcode, String msg, String text, List<String> texts, Shape shape, String category,
                           List<Arg> args, boolean extension, String spec) {
        Template withSpec(String newSpec) {
            return new Template(opcode, msg, text, texts, shape, category, args, extension, newSpec);
        }

        public String spelling() {
            return spec != null ? spec : text;
        }

        public List<Arg> inlineArgs() {
            return args.stream().filter(a -> a.kind() != ArgKind.SUBSTACK && a.kind() != ArgKind.IMAGE).toList();
        }

        public List<Arg> substacks() {
            return args.stream().filter(a -> a.kind() == ArgKind.SUBSTACK).toList();
        }

        public boolean isCBlock() {
            return !substacks().isEmpty();
        }

        public boolean isValue() {
            return shape == Shape.REPORTER || shape == Shape.BOOLEAN;
        }

        public Arg arg(String name) {
            return args.stream().filter(a -> name.equals(a.name())).findFirst().orElse(null);
        }
    }

    private final Map<String, Template> byOpcode = new LinkedHashMap<>();
    private final Map<String, String> opcodeBySbId = new LinkedHashMap<>();

    public BlockTable(ObjectMapper objectMapper) {
        JsonNode root = load(objectMapper, "editor-blocks.json");
        root.path("blocks").properties().forEach(entry -> byOpcode.put(entry.getKey(), template(entry.getValue())));
        root.path("sbIds").properties().forEach(entry -> opcodeBySbId.put(entry.getKey(), entry.getValue().asText()));
        for (Template template : byOpcode.values()) {
            for (Arg arg : template.args()) {
                if (arg.options() != null) {
                    arg.options().stream().filter(o -> o.value().startsWith("_")).forEach(o -> specialWords.add(o.label().toLowerCase(Locale.ROOT)));
                }
            }
        }
        // scratchblocks 的拼写表(commands.json):同一 opcode 有多种拼写时取第一种为标准写法
        for (JsonNode command : load(objectMapper, "commands.json")) {
            String opcode = opcodeBySbId.get(command.path("id").asText());
            Template template = opcode == null ? null : byOpcode.get(opcode);
            if (template != null && template.spec() == null && command.path("spec").isTextual()) {
                byOpcode.put(opcode, template.withSpec(command.get("spec").asText()));
            }
        }
    }

    public Template byOpcode(String opcode) {
        return opcode == null ? null : byOpcode.get(opcode);
    }

    public Template bySbId(String sbId) {
        return sbId == null ? null : byOpcode(opcodeBySbId.get(sbId));
    }

    /** 解析器认得这种积木(拼写表里有它):文本里写出它的名字会被读成积木,而不是变量 */
    public boolean parserKnows(String opcode) {
        return opcode != null && opcodeBySbId.containsValue(opcode);
    }

    public Collection<Template> all() {
        return Collections.unmodifiableCollection(byOpcode.values());
    }

    /** mouse-pointer / edge / random position 这类菜单专用词(值以 _ 开头),不会是角色名 */
    public boolean isSpecialWord(String text) {
        return specialWords.contains(text.toLowerCase(Locale.ROOT));
    }

    private final Set<String> specialWords = new java.util.HashSet<>();

    /** 从 XML 里的影子块类型反查它的菜单定义(菜单影子块自身也是一种积木,第一个槽就是那个下拉) */
    public Arg menuOf(String shadowType) {
        Template menu = byOpcode(shadowType);
        if (menu == null || menu.args().isEmpty()) {
            return null;
        }
        Arg first = menu.args().getFirst();
        return first.kind() == ArgKind.MENU || first.kind() == ArgKind.VARIABLE ? first : null;
    }

    private static Template template(JsonNode node) {
        List<Arg> args = new ArrayList<>();
        for (JsonNode a : node.path("args")) {
            args.add(arg(a));
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode t : node.path("texts")) {
            texts.add(t.path("message").asText());
        }
        return new Template(
                node.path("opcode").asText(),
                node.path("msg").isTextual() ? node.get("msg").asText() : null,
                node.path("text").asText(""),
                texts,
                Shape.valueOf(node.path("shape").asText("statement").toUpperCase(Locale.ROOT)),
                node.path("category").asText(null),
                List.copyOf(args),
                node.hasNonNull("extension"),
                null);
    }

    private static Arg arg(JsonNode a) {
        ArgKind kind;
        try {
            kind = ArgKind.valueOf(a.path("kind").asText("input").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAKind) {
            kind = ArgKind.FIELD; // 影子块自己的数字/角度/颜色/音符字段:只在生成时写值,不参与文本匹配
        }
        Shadow shadow = null;
        if (a.path("shadow").isObject()) {
            JsonNode s = a.get("shadow");
            shadow = new Shadow(s.path("type").asText(), s.path("field").isTextual() ? s.get("field").asText() : null,
                    s.path("value").isTextual() ? s.get("value").asText() : null);
        }
        List<Option> options = null;
        boolean dynamic = false;
        JsonNode optionsNode = a.path("options");
        if (optionsNode.isArray()) {
            options = new ArrayList<>();
            for (JsonNode option : optionsNode) {
                options.add(new Option(option.path(0).asText(), option.path(1).asText()));
            }
        } else if (optionsNode.isTextual()) {
            dynamic = true;
        }
        return new Arg(a.path("name").isTextual() ? a.get("name").asText() : null, kind,
                "Boolean".equals(a.path("check").asText(null)), shadow,
                options == null ? null : List.copyOf(options), dynamic,
                a.path("variableType").isTextual() ? a.get("variableType").asText() : null,
                a.path("src").isTextual() ? a.get("src").asText() : null);
    }

    /** 这些菜单除了表里的固定项,还接受作品里的名字(角色、造型、背景、声音) */
    public static final Set<String> SPRITE_MENUS = Set.of("motion_goto_menu", "motion_glideto_menu", "motion_pointtowards_menu",
            "sensing_touchingobjectmenu", "sensing_distancetomenu", "sensing_of_object_menu", "control_create_clone_of_menu");
    public static final String COSTUME_MENU = "looks_costume";
    public static final String BACKDROP_MENU = "looks_backdrops";
    public static final String SOUND_MENU = "sound_sounds_menu";

    private static JsonNode load(ObjectMapper objectMapper, String name) {
        try (InputStream stream = BlockTable.class.getResourceAsStream("/blockcoding/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("缺少引擎资源 blockcoding/" + name);
            }
            return objectMapper.readTree(stream);
        } catch (IOException exception) {
            throw new IllegalStateException("读取引擎资源失败: " + name, exception);
        }
    }
}
