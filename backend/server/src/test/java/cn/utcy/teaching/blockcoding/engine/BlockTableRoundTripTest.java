package cn.utcy.teaching.blockcoding.engine;

import cn.utcy.teaching.blockcoding.engine.BlockTable.Arg;
import cn.utcy.teaching.blockcoding.engine.BlockTable.ArgKind;
import cn.utcy.teaching.blockcoding.engine.BlockTable.Shape;
import cn.utcy.teaching.blockcoding.engine.BlockTable.Template;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Known;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Result;
import java.util.Set;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 往返自检:表里每一种能用文本写的积木,按它的拼写模板生成一段文本 → 编译成 XML → 打印回文本,必须一字不差。
 * 正向和反向用同一张表的两个方向,任何一方用错了表,往返就对不上;不需要人写用例。
 */
class BlockTableRoundTripTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BlockTable TABLE = new BlockTable(MAPPER);
    private static final SbEngine ENGINE = new SbEngine(new ScriptCompiler(new SbParser(MAPPER), TABLE));
    private static final SbToText TO_TEXT = new SbToText(TABLE, MAPPER);
    private static final Pattern SLOT = Pattern.compile("%\\d+");
    private static final Map<String, String> ICONS = Map.of("@greenFlag", "green flag", "@turnRight", "right", "@turnLeft", "left", "@loopArrow", "");

    @TestFactory
    List<DynamicTest> everyBlockSurvivesTextToXmlToText() {
        List<DynamicTest> tests = new ArrayList<>();
        for (Template template : TABLE.all()) {
            if (template.spec() == null || template.args().stream().anyMatch(a -> a.kind() == ArgKind.LABEL)) {
                continue; // 菜单积木、参数积木、拼写表里没有的旧积木不单独成行
            }
            tests.add(DynamicTest.dynamicTest(template.opcode(), () -> {
                String line = sampleLine(template);
                String program = switch (template.shape()) {
                    case HAT -> line;
                    case REPORTER -> "when green flag clicked\n  say (" + line + ")";
                    case BOOLEAN -> "when green flag clicked\n  wait until <" + line + ">";
                    default -> "when green flag clicked\n  " + line + (template.isCBlock() ? "\n  end" : "");
                };
                Known known = new Known(Set.of("score"), Set.of("items"), List.of(), null);
                Result result = ENGINE.compile(program, known);
                assertTrue(result.ok(), template.opcode() + " 编译失败:\n" + program + "\n" + result.errors());
                assertEquals(program, TO_TEXT.toText(result.xml()), template.opcode() + " 往返后文本不同");
            }));
        }
        return tests;
    }

    static String sampleLine(Template template) {
        String spelling = template.spec();
        for (Map.Entry<String, String> icon : ICONS.entrySet()) {
            spelling = spelling.replace(icon.getKey(), icon.getValue());
        }
        List<Arg> inline = template.inlineArgs();
        Matcher m = SLOT.matcher(spelling);
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (m.find()) {
            Arg arg = i < inline.size() ? inline.get(i) : null;
            i++;
            m.appendReplacement(out, Matcher.quoteReplacement(sample(arg)));
        }
        m.appendTail(out);
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    private static String sample(Arg arg) {
        if (arg == null) {
            return "(1)";
        }
        return switch (arg.kind()) {
            case MENU -> "[" + (arg.options() != null && !arg.options().isEmpty() ? arg.options().getFirst().label() : "backdrop1") + " v]";
            case VARIABLE -> "list".equals(arg.variableType()) ? "[items v]" : "broadcast_msg".equals(arg.variableType()) ? "[go v]" : "[score v]";
            case INPUT -> {
                if (arg.booleanInput()) {
                    yield "<mouse down?>";
                }
                BlockTable.Shadow shadow = arg.shadow();
                if (shadow == null) {
                    yield "[hi]";
                }
                Arg menu = TABLE.menuOf(shadow.type());
                if (menu != null) {
                    yield "[" + (menu.options() != null && !menu.options().isEmpty() ? menu.options().getFirst().label()
                            : "broadcast_msg".equals(menu.variableType()) ? "go" : "costume1") + " v]";
                }
                if (arg.options() != null) {
                    yield "[" + arg.options().getFirst().value() + " v]"; // 扩展菜单按值写(鼓号、乐器号)
                }
                yield switch (shadow.type()) {
                    case "colour_picker" -> "[#ff0000]";
                    case "text" -> "[hi]";
                    default -> "(" + (shadow.value() == null || shadow.value().isEmpty() ? "1" : shadow.value()) + ")";
                };
            }
            default -> "";
        };
    }
}
