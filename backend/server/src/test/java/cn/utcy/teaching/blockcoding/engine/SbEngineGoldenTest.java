package cn.utcy.teaching.blockcoding.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Known;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Procedure;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Result;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 回归样本:176 段输入(含 61 段带错的)与当年 JS 引擎的输出比对——比"结构"不比字节:
 * 同样的 opcode 树、同样的字段值与字面量、同样的出错行号。措辞、属性顺序、参数 id 的拼法允许不同。
 * 转文本样本仍按字节比对。
 */
class SbEngineGoldenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static SbEngine engine;
    private static SbToText toText;

    @BeforeAll
    static void setUp() {
        BlockTable table = new BlockTable(MAPPER);
        engine = new SbEngine(new ScriptCompiler(new SbParser(MAPPER), table));
        toText = new SbToText(table, MAPPER);
    }

    private static JsonNode loadJson(String name) throws Exception {
        try (InputStream stream = SbEngineGoldenTest.class.getResourceAsStream("/blockcoding/" + name)) {
            return MAPPER.readTree(stream);
        }
    }

    @TestFactory
    List<DynamicTest> compileMatchesGoldensStructurally() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode golden : loadJson("goldens-detailed.json")) {
            String src = golden.path("src").asText();
            JsonNode expected = golden.get("result");
            int idx = golden.path("idx").asInt();
            tests.add(DynamicTest.dynamicTest("detailed #" + idx, () -> {
                Result actual = engine.compile(src, known(golden.get("opts")));
                TreeSet<Integer> expectedLines = new TreeSet<>();
                expected.path("errors").forEach(e -> expectedLines.add(e.path("lineNumber").asInt()));
                TreeSet<Integer> actualLines = new TreeSet<>();
                actual.errors().forEach(e -> actualLines.add(e.line()));
                assertEquals(expectedLines, actualLines, "出错行号不同\n输入:\n" + src + "\n实际错误: " + actual.errors());
                if (expectedLines.isEmpty()) {
                    String expectedShape = XmlShape.of(expected.path("xml").asText());
                    String actualShape = XmlShape.of(actual.xml());
                    if (!expectedShape.equals(actualShape)) {
                        String[] e = expectedShape.split("\n");
                        String[] a = actualShape.split("\n");
                        int i = 0;
                        while (i < e.length && i < a.length && e[i].equals(a[i])) {
                            i++;
                        }
                        String context = String.join("\n", java.util.Arrays.asList(e).subList(Math.max(0, i - 3), Math.min(e.length, i + 2)));
                        throw new AssertionError("积木结构不同 #" + idx + "\n输入:\n" + src + "\n--- 期望(第 " + i + " 行附近):\n" + context
                                + "\n--- 实际:\n" + String.join("\n", java.util.Arrays.asList(a).subList(Math.max(0, i - 3), Math.min(a.length, i + 2))));
                    }
                    assertEquals(expected.path("blockCount").asInt(), actual.blockCount(), "积木数不同\n输入:\n" + src);
                }
            }));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> toTextMatchesGoldens() throws Exception {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode golden : loadJson("goldens-totext.json")) {
            String xml = golden.path("xml").asText();
            String expected = golden.path("text").asText();
            int idx = golden.path("idx").asInt();
            tests.add(DynamicTest.dynamicTest("totext #" + idx, () -> assertEquals(expected, toText.toText(xml))));
        }
        return tests;
    }

    private static Known known(JsonNode opts) {
        Set<String> variables = new LinkedHashSet<>();
        Set<String> lists = new LinkedHashSet<>();
        List<Procedure> procs = new ArrayList<>();
        if (opts != null && opts.isObject()) {
            opts.path("knownVariables").forEach(v -> variables.add(v.asText()));
            opts.path("knownLists").forEach(v -> lists.add(v.asText()));
            for (JsonNode proc : opts.path("knownProcs")) {
                List<String> names = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                proc.path("argumentnames").forEach(n -> names.add(n.asText()));
                proc.path("argumentids").forEach(n -> ids.add(n.asText()));
                procs.add(new Procedure(proc.path("proccode").asText(), names, ids));
            }
        }
        return new Known(variables, lists, procs, null);
    }
}
