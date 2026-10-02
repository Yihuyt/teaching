package cn.utcy.teaching.blockcoding.application.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FinalAnswerTextStreamTest {

    private static String collect(List<String> fragments) {
        FinalAnswerTextStream stream = new FinalAnswerTextStream();
        StringBuilder out = new StringBuilder();
        fragments.forEach(f -> out.append(stream.feed(f)));
        return out.toString();
    }

    @Test
    @DisplayName("键、冒号、引号、转义都可能被拆在两片里,拼出来的正文和一次性解析一致")
    void survivesArbitrarySplits() {
        String json = "{\"text\": \"做好了!\\n点绿旗后小猫会\\\"来回走\\\"\\u3002\"}";
        for (int cut1 = 1; cut1 < json.length() - 1; cut1++) {
            for (int cut2 = cut1 + 1; cut2 < json.length(); cut2 += 3) {
                List<String> parts = List.of(json.substring(0, cut1), json.substring(cut1, cut2), json.substring(cut2));
                assertThat(collect(parts)).as("cut at %d,%d", cut1, cut2).isEqualTo("做好了!\n点绿旗后小猫会\"来回走\"。");
            }
        }
    }

    @Test
    @DisplayName("只认第一个 text 键;字符串结束后的内容忽略")
    void ignoresOtherKeysAndTrailingContent() {
        assertThat(collect(List.of("{\"other\": \"x\", \"text\": \"hi\", \"text\": \"again\"}"))).isEqualTo("hi");
        assertThat(collect(List.of("{\"text\":\"a", "b\"}", "{\"text\":\"c\"}"))).isEqualTo("ab");
    }

    @Test
    @DisplayName("每片只返回新解出的部分")
    void returnsIncrements() {
        FinalAnswerTextStream stream = new FinalAnswerTextStream();
        assertThat(stream.feed("{\"te")).isEmpty();
        assertThat(stream.feed("xt\": \"你好")).isEqualTo("你好");
        assertThat(stream.feed(",世界\"}")).isEqualTo(",世界");
        assertThat(stream.feed("garbage")).isEmpty();
    }
}
