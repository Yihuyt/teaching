package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.blockcoding.application.agent.Script;
import cn.utcy.teaching.blockcoding.engine.BlockTable;
import cn.utcy.teaching.blockcoding.engine.SbToText;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectStacksTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ProjectStacks STACKS = new ProjectStacks(new SbToText(new BlockTable(MAPPER), MAPPER));

    @Test
    @DisplayName("工作区 XML 按顶层积木拆成带编号、带 id 的脚本;自定义积木名一并登记")
    void splitsWorkspaceIntoStacks() {
        String cat = "<xml>"
                + "<block type=\"event_whenflagclicked\" id=\"top-a\" x=\"10\" y=\"10\"><next>"
                + "<block type=\"motion_movesteps\" id=\"b2\"><value name=\"STEPS\"><shadow type=\"math_number\" id=\"s1\"><field name=\"NUM\">10</field></shadow></value></block>"
                + "</next></block>"
                + "<block type=\"procedures_definition\" id=\"top-b\" x=\"10\" y=\"200\"><statement name=\"custom_block\">"
                + "<shadow type=\"procedures_prototype\" id=\"p1\"><mutation proccode=\"draw bars\" argumentids=\"[]\" argumentnames=\"[]\" argumentdefaults=\"[]\" warp=\"false\"></mutation></shadow>"
                + "</statement><next><block type=\"pen_clear\" id=\"b3\"></block></next></block>"
                + "</xml>";
        HarvestPayload harvest = new HarvestPayload(
                List.of(new HarvestPayload.SpriteContext("Cat", false, List.of("costume1"), List.of(), List.of(), List.of())),
                List.of(), List.of(), List.of(), Map.of(), "Cat", Map.of("Cat", cat));
        List<Script> stacks = STACKS.of(harvest);
        assertThat(stacks).hasSize(2);
        assertThat(stacks.get(0).id()).isEqualTo(1);
        assertThat(stacks.get(0).blockId()).isEqualTo("top-a");
        assertThat(stacks.get(0).sprite()).isEqualTo("Cat");
        assertThat(stacks.get(0).code()).startsWith("when green flag clicked").contains("move (10) steps");
        assertThat(stacks.get(0).blockCount()).isEqualTo(2);
        assertThat(stacks.get(0).preexisting()).isTrue();
        assertThat(stacks.get(1).blockId()).isEqualTo("top-b");
        assertThat(stacks.get(1).definedProcedures()).containsExactly("draw bars");
        assertThat(stacks.get(1).code()).startsWith("define draw bars");
    }

    @Test
    @DisplayName("空快照 / 空工作区不出脚本")
    void emptyProject() {
        assertThat(STACKS.of(null)).isEmpty();
        HarvestPayload harvest = new HarvestPayload(List.of(), List.of(), List.of(), List.of(), Map.of(), null,
                Map.of("Cat", "<xml></xml>"));
        assertThat(STACKS.of(harvest)).isEmpty();
    }

    @Test
    @DisplayName("拖进对话框的一段积木:按顶层积木转成文本;不是积木的 XML 返回 null")
    void quotedStackFromXml() {
        String xml = "<xml><block type=\"event_whenflagclicked\" id=\"top-q\" x=\"0\" y=\"0\"><next>"
                + "<block type=\"motion_movesteps\" id=\"q2\"><value name=\"STEPS\"><shadow type=\"math_number\" id=\"s9\"><field name=\"NUM\">10</field></shadow></value></block>"
                + "</next></block></xml>";
        Script script = STACKS.fromXml("Cat", xml);
        assertThat(script).isNotNull();
        assertThat(script.sprite()).isEqualTo("Cat");
        assertThat(script.blockId()).isEqualTo("top-q");
        assertThat(script.code()).startsWith("when green flag clicked").contains("move (10) steps");
        assertThat(script.blockCount()).isEqualTo(2);
        assertThat(STACKS.fromXml("Cat", "<xml></xml>")).isNull();
        assertThat(STACKS.fromXml("Cat", "not xml")).isNull();
    }
}
