package cn.utcy.teaching.blockcoding.engine;

import cn.utcy.teaching.blockcoding.engine.BlockTable.Arg;
import cn.utcy.teaching.blockcoding.engine.BlockTable.ArgKind;
import cn.utcy.teaching.blockcoding.engine.BlockTable.Shape;
import cn.utcy.teaching.blockcoding.engine.BlockTable.Template;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class SbToText {
    private static final Pattern SLOT_TOKEN = Pattern.compile("%\\d+");
    private static final Pattern PROC_TOKEN = Pattern.compile("%[snb]");
    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");
    private static final Map<String, String> ICON_WORDS = Map.of(
            "@greenFlag", "green flag", "@turnRight", "right", "@turnLeft", "left", "@loopArrow", "");

    private final BlockTable table;
    private final ObjectMapper objectMapper;
    /** 解析器认得的、没有槽的值积木的写法(size、timer…):变量重名时要加类别标记;解析器不认识的老积木(counter)不用 */
    private final java.util.Set<String> reservedValueTexts;

    public SbToText(BlockTable table, ObjectMapper objectMapper) {
        this.table = table;
        this.objectMapper = objectMapper;
        this.reservedValueTexts = table.all().stream()
                .filter(template -> template.isValue() && template.inlineArgs().isEmpty() && table.parserKnows(template.opcode()))
                .map(template -> template.text().strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ---- 入口 ---------------------------------------------------------------

    public String toText(String xmlString) {
        Document document = BlockXml.parse(xmlString);
        if (document == null) {
            return "";
        }
        Element container = document.getDocumentElement();
        List<String> scripts = new ArrayList<>();
        String declarations = declarations(BlockXml.child(container, "variables"));
        if (declarations != null) {
            scripts.add(declarations);
        }
        for (Element child : BlockXml.children(container, "block")) {
            if (child.getAttribute("type").startsWith("unused_")) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            renderTop(child, lines);
            if (!lines.isEmpty()) {
                scripts.add(String.join("\n", lines));
            }
        }
        return String.join("\n\n", scripts);
    }

    /** scratchblocks 没有声明语法:工作区里的变量/列表以注释行给模型作上下文 */
    private static String declarations(Element variables) {
        if (variables == null) {
            return null;
        }
        List<String> globals = new ArrayList<>();
        List<String> locals = new ArrayList<>();
        List<String> globalLists = new ArrayList<>();
        List<String> localLists = new ArrayList<>();
        for (Element v : BlockXml.children(variables, "variable")) {
            if ("broadcast_msg".equals(v.getAttribute("type"))) {
                continue;
            }
            boolean local = "true".equals(v.getAttribute("islocal"));
            boolean list = "list".equals(v.getAttribute("type"));
            (list ? (local ? localLists : globalLists) : (local ? locals : globals)).add(v.getTextContent());
        }
        List<String> lines = new ArrayList<>();
        if (!globals.isEmpty()) {
            lines.add("// variables (all sprites): " + String.join(", ", globals));
        }
        if (!locals.isEmpty()) {
            lines.add("// variables (this sprite only): " + String.join(", ", locals));
        }
        if (!globalLists.isEmpty()) {
            lines.add("// lists (all sprites): " + String.join(", ", globalLists));
        }
        if (!localLists.isEmpty()) {
            lines.add("// lists (this sprite only): " + String.join(", ", localLists));
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private void renderTop(Element block, List<String> lines) {
        String type = block.getAttribute("type");
        if ("procedures_definition".equals(type)) {
            renderDefinition(block, lines);
            return;
        }
        Template template = table.byOpcode(type);
        if (template != null && template.shape() == Shape.HAT) {
            lines.add(statementLine(block, template));
            renderStatements(BlockXml.nextBlock(block), "  ", lines);
            return;
        }
        renderStatements(block, "", lines);
    }

    // ---- 语句 ---------------------------------------------------------------

    private void renderStatements(Element block, String indent, List<String> lines) {
        for (Element cur = block; cur != null; cur = BlockXml.nextBlock(cur)) {
            renderStatement(cur, indent, lines);
        }
    }

    private void renderStatement(Element block, String indent, List<String> lines) {
        String type = block.getAttribute("type");
        if ("procedures_call".equals(type)) {
            lines.add(indent + renderProcedureCall(block));
            return;
        }
        if ("procedures_definition".equals(type)) {
            lines.add(indent + "// [unsupported here: nested custom-block definition]");
            return;
        }
        Template template = table.byOpcode(type);
        if (template == null) {
            lines.add(indent + "// [unsupported block: " + type + "]");
            return;
        }
        lines.add(indent + statementLine(block, template));
        List<Arg> substacks = template.substacks();
        for (int i = 0; i < substacks.size(); i++) {
            if (i > 0) {
                lines.add(indent + "else");
            }
            Element statement = BlockXml.input(block, substacks.get(i).name());
            renderStatements(statement == null ? null : BlockXml.child(statement, "block"), indent + "  ", lines);
        }
        if (!substacks.isEmpty()) {
            lines.add(indent + "end");
        }
    }

    private String statementLine(Element block, Template template) {
        String spelling = template.spelling();
        for (Map.Entry<String, String> icon : ICON_WORDS.entrySet()) {
            spelling = spelling.replace(icon.getKey(), icon.getValue());
        }
        List<Arg> inline = template.inlineArgs();
        Matcher m = SLOT_TOKEN.matcher(spelling);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (m.find()) {
            Arg arg = i < inline.size() ? inline.get(i) : null;
            i++;
            m.appendReplacement(out, Matcher.quoteReplacement(arg == null ? "()" : renderSlot(block, arg)));
        }
        m.appendTail(out);
        return WHITESPACE_RUN.matcher(out.toString()).replaceAll(" ").trim();
    }

    private String renderSlot(Element block, Arg arg) {
        switch (arg.kind()) {
            case MENU, VARIABLE, LABEL, FIELD -> {
                String value = BlockXml.fieldText(block, arg.name());
                return arg.kind() == ArgKind.LABEL || arg.kind() == ArgKind.FIELD ? value : "[" + escapeLabel(labelOf(arg, value)) + " v]";
            }
            case INPUT -> {
                Element value = BlockXml.childNamed(block, "value", arg.name());
                Element inner = value == null ? null : BlockXml.child(value, "block");
                if (inner != null) {
                    return renderExpression(inner);
                }
                if (arg.booleanInput()) {
                    return "<>";
                }
                Element shadow = value == null ? null : BlockXml.child(value, "shadow");
                Element field = shadow == null ? null : BlockXml.child(shadow, "field");
                String text = field != null ? field.getTextContent() : arg.shadow() != null && arg.shadow().value() != null ? arg.shadow().value() : "";
                String shadowType = shadow != null ? shadow.getAttribute("type") : arg.shadow() != null ? arg.shadow().type() : null;
                if ("colour_picker".equals(shadowType)) {
                    return "[" + text + "]";
                }
                Arg menu = table.menuOf(shadowType);
                if (menu != null) {
                    return "[" + escapeLabel(labelOf(menu, text)) + " v]";
                }
                if (arg.options() != null) {
                    return "[" + escapeLabel(labelOf(arg, text)) + " v]"; // 扩展积木的菜单影子块(pen_menu_colorParam 等)
                }
                // 文字槽里的数值写成 (5) 而非 [5]:两者解析回同一 XML,但 [5] 会诱导模型去怪括号
                if ("text".equals(shadowType)) {
                    return NUMERIC.matcher(text).matches() ? "(" + text + ")" : "[" + quoteText(text) + "]";
                }
                return "(" + text + ")";
            }
            default -> {
                return "";
            }
        }
    }

    /**
     * 菜单值写回学生认识的词:_mouse_ → mouse-pointer、COLOR → color;
     * 值本身就是可写的词(鼓号 1 的标签是 "(1) Snare Drum")时保留值,短
     */
    private static String labelOf(Arg menu, String value) {
        if (menu.options() != null) {
            for (BlockTable.Option option : menu.options()) {
                if (option.value().equals(value) && (value.startsWith("_") || option.label().equalsIgnoreCase(value))) {
                    return option.label();
                }
            }
        }
        return value;
    }

    // ---- 值 -----------------------------------------------------------------

    private String renderExpression(Element block) {
        String type = block.getAttribute("type");
        switch (type) {
            case "data_variable" -> {
                return nameReporter(BlockXml.fieldText(block, "VARIABLE"), "variables");
            }
            case "data_listcontents" -> {
                return nameReporter(BlockXml.fieldText(block, "LIST"), "list");
            }
            case "argument_reporter_string_number" -> {
                return "(" + BlockXml.fieldText(block, "VALUE") + ")";
            }
            case "argument_reporter_boolean" -> {
                return "<" + BlockXml.fieldText(block, "VALUE") + ">";
            }
            default -> {
                Template template = table.byOpcode(type);
                if (template == null) {
                    return "() // [unsupported block: " + type + "]";
                }
                String line = statementLine(block, template);
                return template.shape() == Shape.BOOLEAN ? "<" + line + ">" : "(" + line + ")";
            }
        }
    }

    /** 变量 / 列表读作 (name);名字和某个内置值积木(size、timer、x position…)一样时加类别标记,不然解析回去就成了那块积木 */
    private String nameReporter(String name, String category) {
        String key = name.strip().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
        return "(" + name + (reservedValueTexts.contains(key) ? " :: " + category : "") + ")";
    }

    /** 方括号里的文字:] 和 \ 要转义;结尾的 " v" 会被读成下拉,写成 \v */
    static String quoteText(String text) {
        String out = text.replace("\\", "\\\\").replace("]", "\\]");
        return out.endsWith(" v") ? out.substring(0, out.length() - 1) + "\\v" : out;
    }

    /** 下拉里的名字(变量、造型、角色…):只有 ] 和 \ 会破坏记法 */
    static String escapeLabel(String label) {
        return label.replace("\\", "\\\\").replace("]", "\\]");
    }

    // ---- 自定义积木 ---------------------------------------------------------

    private String renderProcedureCall(Element block) {
        Element mutation = BlockXml.child(block, "mutation");
        String proccode = mutation != null ? mutation.getAttribute("proccode") : "";
        List<String> argumentIds = jsonStrings(mutation != null ? BlockXml.attribute(mutation, "argumentids") : null);
        Matcher m = PROC_TOKEN.matcher(proccode);
        StringBuilder out = new StringBuilder();
        int i = -1;
        while (m.find()) {
            i++;
            String id = i < argumentIds.size() ? argumentIds.get(i) : null;
            Element value = id != null ? BlockXml.childNamed(block, "value", id) : null;
            Element inner = value == null ? null : BlockXml.child(value, "block");
            String replacement;
            if (inner != null) {
                replacement = renderExpression(inner);
            } else if ("%b".equals(m.group())) {
                replacement = "<>";
            } else {
                Element shadow = value == null ? null : BlockXml.child(value, "shadow");
                Element field = shadow == null ? null : BlockXml.child(shadow, "field");
                String text = field != null ? field.getTextContent() : "";
                replacement = NUMERIC.matcher(text).matches() ? "(" + text + ")" : "[" + quoteText(text) + "]";
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return WHITESPACE_RUN.matcher(out.toString()).replaceAll(" ").trim();
    }

    private void renderDefinition(Element block, List<String> lines) {
        Element statement = BlockXml.input(block, "custom_block");
        Element prototype = statement == null ? null : BlockXml.child(statement, "shadow");
        Element mutation = prototype == null ? null : BlockXml.child(prototype, "mutation");
        if (mutation == null) {
            lines.add("// [unsupported block: procedures_definition without prototype]");
            return;
        }
        String proccode = mutation.getAttribute("proccode");
        List<String> names = jsonStrings(BlockXml.attribute(mutation, "argumentnames"));
        Matcher m = PROC_TOKEN.matcher(proccode);
        StringBuilder head = new StringBuilder();
        int i = -1;
        while (m.find()) {
            i++;
            String name = i < names.size() ? names.get(i) : "arg" + (i + 1);
            m.appendReplacement(head, Matcher.quoteReplacement("%b".equals(m.group()) ? "<" + name + ">" : "(" + name + ")"));
        }
        m.appendTail(head);
        lines.add("define " + WHITESPACE_RUN.matcher(head.toString()).replaceAll(" ").trim());
        renderStatements(BlockXml.nextBlock(block), "  ", lines);
    }

    private List<String> jsonStrings(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        try {
            JsonNode array = objectMapper.readTree(json);
            if (array.isArray()) {
                array.forEach(item -> out.add(item.asText()));
            }
        } catch (Exception ignored) {
            out.clear();
        }
        return out;
    }
}
