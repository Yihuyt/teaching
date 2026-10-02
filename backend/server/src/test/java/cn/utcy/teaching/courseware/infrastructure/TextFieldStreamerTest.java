package cn.utcy.teaching.courseware.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TextFieldStreamerTest {

    private String feedAll(List<String> chunks) {
        TextFieldStreamer streamer = new TextFieldStreamer();
        StringBuilder out = new StringBuilder();
        for (String chunk : chunks) {
            out.append(streamer.feed(chunk));
        }
        return out.toString();
    }

    @Test
    @DisplayName("整段喂入:只提取 text 字段内容,actions 不外漏")
    void extractsTextOnly() {
        assertThat(feedAll(List.of(
                "{\"text\":\"你好,同学。\",\"actions\":[{\"type\":\"highlight\",\"target\":\"blk-1\"}]}")))
                .isEqualTo("你好,同学。");
    }

    @Test
    @DisplayName("任意切分:键名、转义符、\\uXXXX 跨块都能正确拼接")
    void survivesArbitraryChunking() {
        assertThat(feedAll(List.of(
                "{\"te", "xt", "\"", ":", " \"a\\", "nb\\u", "4e2d", "c\"", ",\"actions\":[]}")))
                .isEqualTo("a\nb中c");
    }

    @Test
    @DisplayName("转义引号不终止字符串;字段结束后的内容全部忽略")
    void escapedQuoteAndTail() {
        assertThat(feedAll(List.of(
                "{\"text\":\"他说\\\"好\\\"就好\",\"actions\":[],\"text2\":\"不该出现\"}")))
                .isEqualTo("他说\"好\"就好");
    }

    @Test
    @DisplayName("text 前有其他字段也能定位")
    void keyAfterOtherFields() {
        assertThat(feedAll(List.of("{\"actions\":[],\"text\":\"答案\"}"))).isEqualTo("答案");
    }
}
