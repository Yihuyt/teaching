package cn.utcy.teaching.blockcoding.engine;

import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Known;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Procedure;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProcedureXmlTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SbEngine engine = new SbEngine(new ScriptCompiler(new SbParser(MAPPER), new BlockTable(MAPPER)));

    @Test
    @DisplayName("编译出的 define 再读回去:名字、参数名、参数 id 一致;用读回的定义编译调用,调用的 id 和定义相同")
    void roundTripThroughCompiledDefinition() {
        Result define = engine.compile("define jump (height) <fast>\nchange y by (height)", Known.empty());
        assertThat(define.ok()).isTrue();
        List<Procedure> definitions = ProcedureXml.definitions(define.xml());
        assertThat(definitions).singleElement().satisfies(procedure -> {
            assertThat(procedure.proccode()).isEqualTo("jump %s %b");
            assertThat(procedure.argumentNames()).containsExactly("height", "fast");
            assertThat(procedure.argumentIds()).hasSize(2);
        });

        Result call = engine.compile("when green flag clicked\njump (10) <touching [edge v]?>",
                new Known(Set.of(), Set.of(), definitions, null));
        assertThat(call.ok()).isTrue();
        assertThat(call.xml()).contains("argumentids=\"" + escaped(definitions.getFirst().argumentIds()) + "\"");
    }

    @Test
    @DisplayName("编辑器给的 id 原样保留;没有定义、读不出来的 XML 当作没有定义")
    void keepsEditorIdsAndToleratesGarbage() {
        String xml = "<xml><block type=\"procedures_definition\"><statement name=\"custom_block\"><shadow type=\"procedures_prototype\">"
                + "<mutation proccode=\"draw %s\" argumentids=\"[&quot;ID0.9x&quot;]\" argumentnames=\"[&quot;size&quot;]\"/></shadow></statement></block></xml>";
        assertThat(ProcedureXml.definitions(xml)).singleElement().extracting(Procedure::argumentIds).isEqualTo(List.of("ID0.9x"));
        assertThat(ProcedureXml.definitions("<xml><block type=\"motion_movesteps\"/></xml>")).isEmpty();
        assertThat(ProcedureXml.definitions("not xml")).isEmpty();
    }

    private static String escaped(List<String> ids) {
        return ("[" + String.join(",", ids.stream().map(id -> "\"" + id + "\"").toList()) + "]").replace("\"", "&quot;");
    }
}
