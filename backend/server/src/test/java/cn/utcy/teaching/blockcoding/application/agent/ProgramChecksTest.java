package cn.utcy.teaching.blockcoding.application.agent;

import cn.utcy.teaching.blockcoding.engine.BlockTable;
import cn.utcy.teaching.blockcoding.engine.SbEngine;
import cn.utcy.teaching.blockcoding.engine.SbParser;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Known;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProgramChecksTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SbEngine engine = new SbEngine(new ScriptCompiler(new SbParser(MAPPER), new BlockTable(MAPPER)));

    private Script script(int id, String sprite, String code, Known known) {
        Result result = engine.compile(code, known);
        assertThat(result.ok()).as(code + " -> " + result.errors()).isTrue();
        return new Script(id, sprite, code, result.xml(), result.blockCount(), result.newVariables(), result.newLists(),
                List.of(), List.of(), result.broadcasts(), result.definedProcedures(), null, false);
    }

    @Test
    @DisplayName("定义被删了(或定义在别的角色)而调用还在:打回;同一角色里有定义就放行")
    void callsNeedADefinitionInTheSameSprite() {
        Script define = script(1, "Cat", "define draw array\nmove (10) steps", Known.empty());
        Known withDefine = new Known(java.util.Set.of(), java.util.Set.of(), List.of(new ScriptCompiler.Procedure("draw array", List.of(), List.of())), null);
        Script caller = script(2, "Cat", "when green flag clicked\ndraw array", withDefine);

        assertThat(ProgramChecks.finishProblems(List.of(define, caller))).isEmpty();
        assertThat(ProgramChecks.finishProblems(List.of(caller))).singleElement().asString()
                .contains("draw array").contains("没有它的 define");
        Script elsewhere = new Script(1, "Dog", define.code(), define.xml(), define.blockCount(), List.of(), List.of(), List.of(), List.of(), List.of(), define.definedProcedures(), null, false);
        assertThat(ProgramChecks.finishProblems(List.of(elsewhere, caller))).singleElement().asString().contains("不跨角色");
    }
}
