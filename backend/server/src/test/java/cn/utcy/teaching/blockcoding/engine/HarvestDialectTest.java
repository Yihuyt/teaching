package cn.utcy.teaching.blockcoding.engine;

import cn.utcy.teaching.blockcoding.application.HarvestPayload;
import cn.utcy.teaching.blockcoding.application.ProjectStacks;
import cn.utcy.teaching.blockcoding.application.agent.Script;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 编辑器快照(scratch-vm 的 blockToXML)和 Blockly 的 XML 不是一种写法:子脚本槽和自定义积木原型都写成 value 而不是 statement,
 * 输入槽里积木在前、影子在后,顶层积木之间还夹着逗号。harvest-sample.xml 是真实编辑器抓下来的样本。
 * 之前只认 statement,模型看到的作品里循环体全是空的、define 显示为不支持,据此"修"出来的都是错的。
 */
class HarvestDialectTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final BlockTable table = new BlockTable(MAPPER);
    private final SbToText toText = new SbToText(table, MAPPER);

    private static String sample() {
        try (InputStream in = HarvestDialectTest.class.getResourceAsStream("/blockcoding/harvest-sample.xml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    @DisplayName("快照转文本:循环体、条件体、自定义积木的定义和调用都在")
    void snapshotKeepsBodiesAndDefinitions() {
        String text = toText.toText(sample());
        assertThat(text).contains("define draw (n)\n  move (n) steps");
        assertThat(text).contains("repeat (8)\n    move (10) steps\n    draw (5)\n  end");
        assertThat(text).contains("if <touching [edge v]?> then\n    say [hi]\n  end");
        assertThat(text).doesNotContain("unsupported");
    }

    @Test
    @DisplayName("登记簿开局的脚本按快照拆:两段、各自的顶层 id、自定义积木名和积木数都对")
    void projectStacksFromSnapshot() {
        HarvestPayload harvest = new HarvestPayload(List.of(), List.of(), List.of(), List.of(), Map.of(), "角色1", Map.of("角色1", sample()));
        List<Script> scripts = new ProjectStacks(toText).of(harvest);
        assertThat(scripts).hasSize(2);
        assertThat(scripts.get(0).definedProcedures()).containsExactly("draw %s");
        assertThat(scripts.get(0).blockId()).isNotBlank();
        assertThat(scripts.get(1).code()).startsWith("when green flag clicked");
        assertThat(scripts.get(1).blockCount()).isEqualTo(7);
    }
}
