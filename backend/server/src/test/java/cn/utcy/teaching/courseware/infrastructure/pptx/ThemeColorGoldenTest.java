package cn.utcy.teaching.courseware.infrastructure.pptx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主题颜色跨语言金样:theme-golden/colors.json 由 TS 真源
 * (frontend/src/features/courseware/layout/theme.ts 的 DEFAULT_THEME.colors,经 dump-theme-golden.mts)生成,
 * 锁定 PptxWriter 颜色常量与屏幕渲染逐值一致。
 */
class ThemeColorGoldenTest {

    private static JsonNode golden() throws Exception {
        try (InputStream in = ThemeColorGoldenTest.class
                .getResourceAsStream("/theme-golden/colors.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    private static String hex(Color color) {
        return String.format("#%06x", color.getRGB() & 0xFFFFFF);
    }

    @Test
    @DisplayName("基础色与主题真源一致")
    void baseColorsMatch() throws Exception {
        JsonNode g = golden();
        assertThat(hex(PptxWriter.TEXT)).isEqualTo(g.path("text").asText());
        assertThat(hex(PptxWriter.MUTED)).isEqualTo(g.path("muted").asText());
        assertThat(hex(PptxWriter.PRIMARY)).isEqualTo(g.path("primary").asText());
        assertThat(hex(PptxWriter.CODE_BG)).isEqualTo(g.path("codeBackground").asText());
        assertThat(hex(PptxWriter.TABLE_BORDER)).isEqualTo(g.path("tableBorder").asText());
        assertThat(hex(PptxWriter.TABLE_HEADER_BG)).isEqualTo(g.path("tableHeaderBackground").asText());
    }

    @Test
    @DisplayName("强调框四组配色与主题真源一致")
    void calloutColorsMatch() throws Exception {
        JsonNode callout = golden().path("callout");
        for (String variant : List.of("info", "tip", "warning", "conclusion")) {
            Color[] pair = PptxWriter.CALLOUT_COLORS.get(variant);
            assertThat(hex(pair[0])).as(variant + ".border")
                    .isEqualTo(callout.path(variant).path("border").asText());
            assertThat(hex(pair[1])).as(variant + ".background")
                    .isEqualTo(callout.path(variant).path("background").asText());
        }
    }

    @Test
    @DisplayName("图表调色板与主题真源一致(ECharts 与 pptx 同源)")
    void chartPaletteMatches() throws Exception {
        JsonNode chart = golden().path("chart");
        assertThat(PptxWriter.CHART_PALETTE).hasSize(chart.size());
        for (int i = 0; i < chart.size(); i++) {
            assertThat(hex(PptxWriter.CHART_PALETTE[i])).as("chart[%d]", i)
                    .isEqualTo(chart.get(i).asText());
        }
    }
}
