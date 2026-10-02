package cn.utcy.teaching.blockcoding.application.agent;

import cn.utcy.teaching.blockcoding.application.HarvestPayload;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Known;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Procedure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自定义积木的参数 id 必须跟着定义走:调用按定义的 id 对齐,对不上时运行期参数全取默认值(空),程序静默出错。
 * 作品里原有的定义用编辑器给的 id(在脚本 XML 里),快照签名只是补充;登记簿里的定义不能被"按名字算"的 id 顶掉。
 */
class WorkspaceProceduresTest {

    private static final String JUMP_XML = """
            <xml><block type="procedures_definition" id="def-1"><statement name="custom_block">
            <shadow type="procedures_prototype"><mutation proccode="jump %s" argumentids="[&quot;editor-id-7&quot;]" argumentnames="[&quot;height&quot;]" argumentdefaults="[&quot;&quot;]" warp="false"/>
            <value name="editor-id-7"><shadow type="argument_reporter_string_number"><field name="VALUE">height</field></shadow></value>
            </shadow></statement></block></xml>""";

    private static final HarvestPayload.SpriteContext CAT = new HarvestPayload.SpriteContext("Cat", false, List.of("costume1"), List.of(), List.of(), List.of());

    @Test
    @DisplayName("作品里原有的定义:参数 id 取脚本 XML 里编辑器给的,快照签名只补登记簿里没有的自定义积木")
    void projectDefinitionsKeepEditorIds() {
        HarvestPayload harvest = new HarvestPayload(List.of(CAT), List.of(), List.of(), List.of(),
                Map.of("Cat", List.of(
                        new HarvestPayload.ProcedureSignature("jump %s", List.of("height"), List.of("stale-id"), List.of("")),
                        new HarvestPayload.ProcedureSignature("spin", List.of(), List.of(), List.of()))),
                "Cat", Map.of());
        Project project = Project.fromHarvest(harvest, List.of(Script.preexisting(1, "Cat", "define jump (height)", JUMP_XML, 2, List.of("jump %s"), "def-1")));
        Known known = project.knownFor("Cat", true);
        assertThat(known.procedures()).extracting(Procedure::proccode).containsExactly("jump %s", "spin");
        assertThat(known.procedures().getFirst().argumentIds()).containsExactly("editor-id-7");
        assertThat(known.procedures().getFirst().argumentNames()).containsExactly("height");
    }

    @Test
    @DisplayName("空工作台上加的定义:参数 id 同样从脚本 XML 读,不再是空的")
    void sandboxDefinitionsCarryIds() {
        Project project = Project.empty();
        project.createSprite(Sprite.bare("Cat"));
        project.add(new Script(1, "Cat", "define jump (height)", JUMP_XML, 2, List.of(), List.of(), List.of(), List.of(), List.of(), List.of("jump %s"), null, false));
        Known known = project.knownFor("Cat", false);
        assertThat(known.procedures()).singleElement().satisfies(procedure -> {
            assertThat(procedure.proccode()).isEqualTo("jump %s");
            assertThat(procedure.argumentIds()).containsExactly("editor-id-7");
        });
    }
}
