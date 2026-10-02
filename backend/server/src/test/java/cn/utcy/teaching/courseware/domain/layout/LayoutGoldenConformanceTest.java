package cn.utcy.teaching.courseware.domain.layout;

import cn.utcy.teaching.courseware.domain.Stage;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TS ↔ Java 布局引擎一致性金标准:cases.json 由
 * frontend/tests/golden/dump-layout-golden.mjs 用 TS 引擎(唯一事实源)导出,
 * 本测试逐帧比对 Java 引擎输出。任何不一致都应修 Java 侧,而不是改金标准。
 */
class LayoutGoldenConformanceTest {

    /** 双端同为 IEEE754 double 且运算序一致,理论上比特相同;容差仅吸收 JSON 十进制往返 */
    private static final double EPS = 1e-6;

    private final LayoutEngine engine = new LayoutEngine();

    @TestFactory
    List<DynamicTest> goldenCases() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                // @JsonTypeInfo(visible = true) 会把 type 字段传入 record 构造匹配,需忽略
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        JsonNode cases;
        try (InputStream in = getClass().getResourceAsStream("/layout-golden/cases.json")) {
            assertNotNull(in, "缺少金标准资产 layout-golden/cases.json,先在 frontend 下运行 node --experimental-strip-types tests/golden/dump-layout-golden.mjs");
            cases = mapper.readTree(in);
        }
        assertTrue(cases.isArray() && cases.size() > 0, "金标准用例为空");

        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode caseNode : cases) {
            String name = caseNode.get("name").asText();
            tests.add(DynamicTest.dynamicTest(name, () -> runCase(mapper, caseNode)));
        }
        return tests;
    }

    private void runCase(ObjectMapper mapper, JsonNode caseNode) throws Exception {
        Stage.Scene scene = mapper.treeToValue(caseNode.get("scene"), Stage.Scene.class);
        JsonNode expected = caseNode.get("positioned");

        LayoutEngine.PositionedScene actual = engine.layoutScene(scene);

        assertEquals(expected.get("sceneId").asText(), actual.sceneId(), "sceneId");
        assertEquals(expected.get("preset").asText(), actual.preset(), "preset");
        assertEquals(expected.get("fontScale").asDouble(), actual.fontScale(), "fontScale 必须逐位一致");
        assertEquals(expected.get("overflow").asText(), actual.overflow(), "overflow");

        assertTitle(expected.get("title"), actual.title());
        assertFrames(expected.get("frames"), actual.frames());

        // describeOverflow 的中文诊断文本逐字符锁定
        JsonNode expectedMsg = caseNode.get("overflowMessage");
        if (expectedMsg == null || expectedMsg.isNull()) {
            assertNull(engine.describeOverflow(scene), "describeOverflow 应为 null");
        } else {
            assertEquals(expectedMsg.asText(), engine.describeOverflow(scene), "describeOverflow");
        }
    }

    private void assertTitle(JsonNode expected, LayoutEngine.TitleFrame actual) {
        if (expected == null || expected.isNull()) {
            assertNull(actual, "title 应为 null");
            return;
        }
        assertNotNull(actual, "title 不应为 null");
        assertEquals(expected.get("x").asDouble(), actual.x(), EPS, "title.x");
        assertEquals(expected.get("y").asDouble(), actual.y(), EPS, "title.y");
        assertEquals(expected.get("w").asDouble(), actual.w(), EPS, "title.w");
        assertEquals(expected.get("h").asDouble(), actual.h(), EPS, "title.h");
        assertEquals(expected.get("fontSize").asDouble(), actual.fontSize(), EPS, "title.fontSize");
        assertEquals(expected.get("align").asText(), actual.align(), "title.align");

        JsonNode expLines = expected.get("lines");
        assertEquals(expLines.size(), actual.lines().size(), "title.lines 行数");
        for (int i = 0; i < expLines.size(); i++) {
            assertEquals(expLines.get(i).asText(), actual.lines().get(i), "title.lines[" + i + "]");
        }
    }

    private void assertFrames(JsonNode expected, List<LayoutEngine.Frame> actual) {
        assertEquals(expected.size(), actual.size(), "frames 数量");
        for (int i = 0; i < expected.size(); i++) {
            JsonNode e = expected.get(i);
            LayoutEngine.Frame a = actual.get(i);
            String at = "frames[" + i + "](" + e.get("blockId").asText() + ")";
            assertEquals(e.get("blockId").asText(), a.blockId(), at + ".blockId(顺序必须一致)");
            assertEquals(e.get("x").asDouble(), a.x(), EPS, at + ".x");
            assertEquals(e.get("y").asDouble(), a.y(), EPS, at + ".y");
            assertEquals(e.get("w").asDouble(), a.w(), EPS, at + ".w");
            assertEquals(e.get("h").asDouble(), a.h(), EPS, at + ".h");
            assertEquals(e.get("fontScale").asDouble(), a.fontScale(), EPS, at + ".fontScale");
            assertEquals(e.get("pinned").asBoolean(), a.pinned(), at + ".pinned");
        }
    }
}
