package cn.utcy.teaching.blockcoding.application.agent;

import cn.utcy.teaching.blockcoding.application.HarvestPayload;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Known;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectTest {
    private static final String SET_SCORE_XML = "<xml><block type=\"event_whenflagclicked\"><next><block type=\"data_setvariableto\">"
            + "<field name=\"VARIABLE\">score</field><value name=\"VALUE\"><shadow type=\"text\"><field name=\"TEXT\">0</field></shadow></value></block></next></block></xml>";

    private static HarvestPayload harvest() {
        return new HarvestPayload(List.of(
                new HarvestPayload.SpriteContext("Cat", false, List.of("costume1"), List.of("Meow"), List.of("speed"), List.of()),
                new HarvestPayload.SpriteContext("Stage", true, List.of("backdrop1"), List.of(), List.of(), List.of())),
                List.of("score"), List.of("items"), List.of("go"), Map.of(), "Cat", Map.of());
    }

    private static Script written(int id, String sprite, String code, List<String> variables, List<String> localVariables) {
        return new Script(id, sprite, code, SET_SCORE_XML, 2, variables, List.of(), localVariables, List.of(), List.of(), List.of(), null, false);
    }

    @Test
    @DisplayName("开局:快照里的角色、私有名字、全局名字都在;可见变量 = 全局 + 该角色私有")
    void seededFromHarvest() {
        Project project = Project.fromHarvest(harvest(), List.of());
        assertThat(project.sprites()).extracting(Sprite::name).containsExactly("Cat", "Stage");
        assertThat(project.variablesVisibleIn("Cat")).containsExactly("score", "speed");
        assertThat(project.variablesVisibleIn("Stage")).containsExactly("score");
        assertThat(project.listsVisibleIn("Cat")).containsExactly("items");
        assertThat(project.broadcasts()).containsExactly("go");
        Known known = project.knownFor("Cat", true);
        assertThat(known.names().costumes()).containsExactly("costume1");
        assertThat(project.knownFor("Cat", false).names()).isNull();
        assertThat(project.knownFor("Stage", true).privateVariablesElsewhere()).containsEntry("speed", "Cat");
        assertThat(known.privateVariablesElsewhere()).isEmpty();
    }

    @Test
    @DisplayName("写脚本时新建的名字登记为改动:全局的进全局,声明为私有的挂在角色上")
    void scriptsRegisterNames() {
        Project project = Project.fromHarvest(harvest(), List.of());
        project.add(written(1, "Cat", "when green flag clicked\nset [lives v] to (3)", List.of("lives"), List.of("mine")));
        assertThat(project.globalVariables()).contains("lives");
        assertThat(project.sprite("Cat").localVariables()).containsExactly("speed", "mine");
        assertThat(project.diff().createdVariables()).extracting(VariableChange::name).containsExactly("lives", "mine");
        assertThat(project.diff().createdVariables().get(1).sprite()).isEqualTo("Cat");
    }

    @Test
    @DisplayName("撤掉本轮写的脚本:它单独新建的名字一并撤掉,别的脚本还在用的留着")
    void withdrawingAScriptWithdrawsItsOwnNames() {
        Project project = Project.fromHarvest(harvest(), List.of());
        project.add(written(1, "Cat", "when green flag clicked\nset [score v] to (0)", List.of("lives"), List.of("mine")));
        project.add(written(2, "Cat", "when green flag clicked\nset [score v] to (0)", List.of("shared"), List.of()));
        assertThat(project.globalVariables()).contains("lives", "shared");
        assertThat(project.delete(project.find(1))).isNull();
        assertThat(project.globalVariables()).doesNotContain("lives").contains("shared");
        assertThat(project.sprite("Cat").localVariables()).doesNotContain("mine");
        assertThat(project.diff().createdVariables()).extracting(VariableChange::name).containsExactly("shared");
    }

    @Test
    @DisplayName("删变量:还有脚本用着就不删并列出用它的脚本;没人用才删;本轮建的撤记录,原有的记删除")
    void deleteVariableRespectsUsers() {
        Project project = Project.fromHarvest(harvest(), List.of());
        project.add(written(1, "Cat", "when green flag clicked\nset [score v] to (0)", List.of(), List.of()));
        assertThat(project.usersOf("score", false)).extracting(Script::id).containsExactly(1);
        assertThat(project.usersOf("score", false, null)).extracting(Script::id).containsExactly(1);
        assertThat(project.usersOf("score", false, "Cat")).isEmpty();
        assertThat(project.holdersOf("score", false)).containsExactly((String) null);
        assertThat(project.holdersOf("speed", false)).containsExactly("Cat");
        assertThat(project.deleteVariable("score", false)).isFalse();
        assertThat(project.deleteVariable("speed", false)).isTrue();
        assertThat(project.sprite("Cat").localVariables()).isEmpty();
        assertThat(project.diff().deletedVariables()).singleElement().satisfies(change -> {
            assertThat(change.name()).isEqualTo("speed");
            assertThat(change.sprite()).isEqualTo("Cat");
        });
        project.createVariable("temp", false, null);
        assertThat(project.deleteVariable("temp", false)).isTrue();
        assertThat(project.diff().deletedVariables()).hasSize(1);
        assertThat(project.diff().createdVariables()).isEmpty();
    }

    @Test
    @DisplayName("删角色:连同它的脚本;本轮新建的整个撤销,原有的记删除且标明原有")
    void deleteSprite() {
        Project project = Project.fromHarvest(harvest(), List.of(
                Script.preexisting(1, "Cat", "when green flag clicked\nmove (10) steps", "<xml/>", 2, List.of(), "top-1")));
        project.createSprite("Apple");
        project.add(written(2, "Apple", "when green flag clicked\nhide", List.of(), List.of()));
        assertThat(project.deleteSprite("Apple")).hasSize(1);
        assertThat(project.sprite("Apple")).isNull();
        assertThat(project.diff().createdSprites()).isEmpty();
        assertThat(project.diff().deletedSprites()).isEmpty();
        assertThat(project.changes()).as("本轮写在新角色里的脚本随角色一起撤销").isEmpty();

        assertThat(project.deleteSprite("Cat")).hasSize(1);
        assertThat(project.diff().deletedSprites()).singleElement().satisfies(change -> {
            assertThat(change.sprite().name()).isEqualTo("Cat");
            assertThat(change.preexisting()).isTrue();
        });
        assertThat(project.changes()).singleElement().satisfies(change -> assertThat(change.kind()).isEqualTo(ScriptChange.Kind.DELETED));
        assertThat(project.diff().deletedVariables()).extracting(VariableChange::name).containsExactly("speed");
    }

    @Test
    @DisplayName("空工作台:什么都没有;之前回复里定义过的自定义积木带进来后每个角色都能调用,不算别的角色定义的")
    void emptyWorkbenchInheritsProcedures() {
        Project project = Project.empty();
        assertThat(project.isEmpty()).isTrue();
        assertThat(project.all()).isEmpty();
        project.inheritProcedures(List.of(new cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Procedure("jump %s", List.of("height"), List.of("arg-1"))));
        project.createSprite(Sprite.bare("小猫"));
        project.createSprite(Sprite.bare("小狗"));
        assertThat(project.isEmpty()).isFalse();
        Known known = project.knownFor("小猫", false);
        assertThat(known.procedures()).extracting(p -> p.proccode()).containsExactly("jump %s");
        assertThat(known.proceduresElsewhere()).isEmpty();
    }

    @Test
    @DisplayName("给模型看的样子:一个角色一块,脚本挂在角色下面,最后是全局;紧凑版只列编号首行")
    void describeGroupsBySprite() {
        Project project = Project.fromHarvest(harvest(), List.of(
                Script.preexisting(1, "Cat", "when green flag clicked\nmove (10) steps", "<xml/>", 2, List.of(), "top-1")));
        StringBuilder sb = new StringBuilder();
        project.describe(sb, "作品");
        assertThat(sb.toString()).isEqualTo("""
                ## 作品(编号只在这一轮有效)
                ### 角色 Cat
                - 造型:costume1;声音:Meow;私有变量:speed;私有列表:无
                #### 脚本 #1
                ```
                when green flag clicked
                move (10) steps
                ```
                ### 舞台 Stage
                - 造型:backdrop1;声音:无
                - 脚本:无
                ## 全局
                - 变量:score;列表:items;广播:go
                - 用户此刻在编辑器里选中的角色:Cat
                """);
        assertThat(project.summary("作品", "作品里已有")).contains("- #1 when green flag clicked(2 个积木,作品里已有)");
    }
}
