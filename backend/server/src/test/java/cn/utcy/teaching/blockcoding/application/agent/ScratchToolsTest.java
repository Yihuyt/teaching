package cn.utcy.teaching.blockcoding.application.agent;

import cn.utcy.teaching.ai.agent.ChatAgentLoop.ToolResult;
import cn.utcy.teaching.blockcoding.application.agent.SkillCatalog;
import cn.utcy.teaching.blockcoding.application.HarvestPayload;
import cn.utcy.teaching.blockcoding.engine.BlockTable;
import cn.utcy.teaching.blockcoding.engine.SbEngine;
import cn.utcy.teaching.blockcoding.engine.SbParser;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static cn.utcy.teaching.blockcoding.application.agent.ScratchTools.STUCK_RESUBMITS;
import static org.assertj.core.api.Assertions.assertThat;

class ScratchToolsTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SbEngine ENGINE = new SbEngine(new ScriptCompiler(new SbParser(MAPPER), new BlockTable(MAPPER)));
    private static final ScratchTools TOOLS = new ScratchTools(ENGINE, new SkillCatalog("blockcoding/skills"), MAPPER);

    private static HarvestPayload project(String... sprites) {
        List<HarvestPayload.SpriteContext> contexts = new java.util.ArrayList<>();
        for (String sprite : sprites) {
            contexts.add(new HarvestPayload.SpriteContext(sprite, false, List.of("costume1"), List.of("Meow"), List.of(), List.of()));
        }
        contexts.add(new HarvestPayload.SpriteContext("Stage", true, List.of("backdrop1"), List.of(), List.of(), List.of()));
        return new HarvestPayload(contexts, List.of(), List.of(), List.of(), Map.of(), sprites[0], Map.of());
    }

    private static String json(Map<String, Object> args) {
        try {
            return MAPPER.writeValueAsString(args);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    @DisplayName("write_script:通过即登记到目标角色,并报告新建的变量")
    void writeScriptRegisters() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        ToolResult result = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when green flag clicked\nset [score v] to (0)\nsay (score)")));
        assertThat(result.isError()).isFalse();
        assertThat(result.content()).contains("角色 Cat").contains("新建变量:score");
        assertThat(scripts.all()).hasSize(1);
        assertThat(scripts.all().getFirst().sprite()).isEqualTo("Cat");
        assertThat(scripts.all().getFirst().xml()).isNotBlank();
        assertThat(scripts.all().getFirst().blockId()).as("提交前还没有编辑器给的 id").isNull();
        assertThat(scripts.changes()).singleElement().extracting(ScriptChange::kind).isEqualTo(ScriptChange.Kind.WRITTEN);
    }

    @Test
    @DisplayName("write_script:角色不存在 → 报错并列出现有角色,不登记")
    void unknownSpriteIsAnError() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        ToolResult result = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Dog", "code", "when green flag clicked\nsay [hi]")));
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Dog").contains("Cat");
        assertThat(scripts.all()).isEmpty();
    }

    @Test
    @DisplayName("write_script:编译错误带行号回给模型;同一段原样重交第二次被点破")
    void compileErrorsAreReadableAndRepeatsAreCalledOut() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        String code = "when green flag clicked\nrepeat (10)\n  move (10) steps";
        ToolResult first = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Cat", "code", code)));
        assertThat(first.isError()).isTrue();
        assertThat(first.content()).contains("第 2 行").contains("end");
        ToolResult second = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Cat", "code", code)));
        assertThat(second.content()).contains("和上次提交的一样");
        assertThat(scripts.all()).isEmpty();
    }

    @Test
    @DisplayName("后写的脚本能读到先写脚本创建的变量(存在性检查跨脚本累计)")
    void laterScriptsSeeEarlierVariables() {
        Project scripts = Project.fromHarvest(project("Cat", "Dog"), List.of());
        TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when green flag clicked\nset [lives v] to (3)")));
        ToolResult reader = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Dog", "code", "when green flag clicked\nwait until <(lives) < (1)>\nsay [over]")));
        assertThat(reader.isError()).as(reader.content()).isFalse();
        assertThat(scripts.all()).hasSize(2);
    }

    @Test
    @DisplayName("舞台不能用运动/画笔类积木;replaces 用新版取代旧脚本;同帽子的新脚本只提醒;一字不差的重交不算新脚本")
    void stageLimitsAndReplaces() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        HarvestPayload project = project("Cat");
        ToolResult stage = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Stage", "code", "when green flag clicked\nmove (10) steps")));
        assertThat(stage.isError()).isTrue();
        assertThat(stage.content()).contains("舞台没有的积木");
        TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when green flag clicked\nmove (10) steps")));
        ToolResult replaced = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when green flag clicked\nmove (20) steps", "replaces", 1)));
        assertThat(replaced.isError()).as(replaced.content()).isFalse();
        assertThat(replaced.content()).startsWith("已替换脚本 #1");
        assertThat(scripts.all()).hasSize(1);
        assertThat(scripts.all().getFirst().code()).contains("move (20) steps");
        // 没给 replaces 的同帽子脚本是并列的新脚本:只说"两段会一起运行",不替模型猜
        ToolResult revised = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when green flag clicked\nmove (20) steps\nsay [hi]\nwait (1) seconds")));
        assertThat(revised.content()).startsWith("已写入作品的脚本 #2").contains("已有同帽子的脚本 #1").contains("两段会一起运行");
        assertThat(scripts.all()).hasSize(2);
        ToolResult again = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when green flag clicked\n// 走\nmove (20) steps\nsay [hi]\nwait (1) seconds")));
        assertThat(again.content()).startsWith("脚本 #2 没有变化");
        assertThat(scripts.all()).hasSize(2);
    }

    @Test
    @DisplayName("write_script:replaces 的确认里写明换掉的是哪段;帽子不同就点明那段功能没了")
    void replaceConfirmationNamesTheOldScript() {
        Project scripts = Project.fromHarvest(project("Mole"), List.of());
        TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Mole", "code", "when this sprite clicked\nchange [score v] by (1)")));
        ToolResult replaced = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Mole", "replaces", 1, "code", "when green flag clicked\nhide")));
        assertThat(replaced.isError()).isFalse();
        assertThat(replaced.content()).startsWith("已替换脚本 #1")
                .contains("换掉的 #1 原来是「when this sprite clicked」(2 个积木)")
                .contains("帽子不同");
        ToolResult sameHat = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Mole", "replaces", 1, "code", "when green flag clicked\nshow")));
        assertThat(sameHat.content()).contains("原来是「when green flag clicked」").doesNotContain("帽子不同");
    }

    @Test
    @DisplayName("write_script:删掉再原样写回反复三次,循环只给收尾工具")
    void deleteAndRewriteLoopWrapsUp() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        String code = "when green flag clicked\nmove (10) steps";
        ToolResult last = null;
        // 第一次写不算;之后每次"删了再原样写回"计一次,到第 STUCK_RESUBMITS 次收尾
        for (int round = 0; round <= STUCK_RESUBMITS; round++) {
            ToolResult written = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                    json(Map.of("sprite", "Cat", "code", code)));
            assertThat(written.isError()).isFalse();
            if (round > 0) {
                assertThat(written.content()).contains("删了再写等于没改");
            }
            last = written;
            if (round < STUCK_RESUBMITS) {
                int id = scripts.all().getFirst().id();
                TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_SCRIPT, json(Map.of("id", id)));
            }
        }
        assertThat(last.wrapUp()).isTrue();
        assertThat(last.content()).contains("final_answer");
        assertThat(scripts.all()).hasSize(1);
    }

    @Test
    @DisplayName("write_script:没有帽子的脚本被拒;stop / delete this clone 之后的积木(隔着注释行也算)是编译错误")
    void hatRequiredAndUnreachableCalledOut() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        ToolResult hatless = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "forever\n  move (5) steps\nend")));
        assertThat(hatless.isError()).isTrue();
        assertThat(hatless.content()).contains("不是帽子积木");
        assertThat(scripts.all()).isEmpty();

        ToolResult clone = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code",
                        "when I start as a clone\nwait until <touching [edge v]?>\ndelete this clone\n// 检查\nchange [score v] by (1)")));
        assertThat(clone.isError()).isTrue();
        assertThat(clone.content()).contains("第 5 行").contains("后面接不上任何积木");
        assertThat(scripts.all()).isEmpty();

        ToolResult fine = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code",
                        "when green flag clicked\nif <touching [edge v]?> then\n  stop [this script v]\nend\nmove (5) steps")));
        assertThat(fine.isError()).as(fine.content()).isFalse();
    }

    @Test
    @DisplayName("write_script:别的角色有同帽子的脚本是正常的,不提醒;绿旗下只 add 不清空的列表会被提醒")
    void crossSpriteDuplicateAndUnclearedListAreCalledOut() {
        Project scripts = Project.fromHarvest(project("Cat", "Bar"), List.of());
        String init = "when green flag clicked\nrepeat (8)\n  add (pick random (1) to (100)) to [numbers v]\nend\nbroadcast [draw v]";
        ToolResult first = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Stage", "code", init)));
        assertThat(first.isError()).isFalse();
        assertThat(first.content()).contains("提醒:这段绿旗脚本往列表 numbers 里 add");

        ToolResult moved = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Bar", "code", init + "\nsay [开始] for (1) seconds")));
        assertThat(moved.isError()).isFalse();
        assertThat(moved.content()).doesNotContain("注意:角色").doesNotContain("replaces:1");
        assertThat(scripts.all()).hasSize(2);

        ToolResult cleared = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Bar", "replaces", 1,
                        "code", "when green flag clicked\ndelete all of [numbers v]\nadd (1) to [numbers v]")));
        assertThat(cleared.isError()).isFalse();
        assertThat(cleared.content()).doesNotContain("提醒:这段绿旗脚本").doesNotContain("注意:角色");
        assertThat(scripts.all()).hasSize(2);

        // 叫 x / y 的变量几乎总是把变量当成了运动积木
        ToolResult axis = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when this sprite clicked\nset [y v] to (180)\nchange [y v] by (-5)")));
        assertThat(axis.isError()).isFalse();
        assertThat(axis.content()).contains("叫 y 的变量").contains("坐标无关");
    }

    @Test
    @DisplayName("write_script:先 define 的自定义积木能在同角色后写的脚本里调用;重写 define 取代旧定义")
    void defineThenCallAndRedefine() {
        for (String name : List.of("draw_bars", "draw bars")) {
            Project scripts = Project.fromHarvest(project("Cat"), List.of());
            ToolResult defined = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                    json(Map.of("sprite", "Cat", "code", "define " + name + "\nerase all\nsay [hi]")));
            assertThat(defined.isError()).as(name).isFalse();
            ToolResult call = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                    json(Map.of("sprite", "Cat", "code", "when green flag clicked\n" + name)));
            assertThat(call.isError()).as(name + ": " + call.content()).isFalse();
            assertThat(scripts.all().getFirst().definedProcedures()).containsExactly(name);

            ToolResult redefined = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                    json(Map.of("sprite", "Cat", "code", "define " + name + "\nsay [changed]")));
            assertThat(redefined.content()).contains("旧定义已被这版取代");
            assertThat(scripts.all()).hasSize(2);

            ToolResult unchanged = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                    json(Map.of("sprite", "Cat", "code", "define " + name + "\nsay [changed]")));
            assertThat(unchanged.isError()).isFalse();
            assertThat(unchanged.content()).contains("没有变化");
            assertThat(scripts.all()).hasSize(2);
        }
    }

    @Test
    @DisplayName("作品里已有的脚本带编号进登记簿:replaces 取代它,产物只含本轮的改动;同帽子的新脚本只提醒")
    void existingStacksCanBeRewritten() {
        Script existing = Script.preexisting(1, "Cat",
                "when green flag clicked\nforever\n  move (10) steps\n  if on edge, bounce\nend", "<xml/>", 4, List.of(), "top-1");
        Script other = Script.preexisting(2, "Cat",
                "when [space v] key pressed\nsay [hi]", "<xml/>", 2, List.of(), "top-2");
        Project scripts = Project.fromHarvest(project("Cat"), List.of(existing, other));
        assertThat(scripts.changes()).isEmpty();
        ToolResult listed = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.LIST_PROJECT, "{}");
        assertThat(listed.content()).contains("- #1 when green flag clicked").contains("作品里已有");

        ToolResult rewritten = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "replaces", 1,
                        "code", "when green flag clicked\nforever\n  move (20) steps\n  if on edge, bounce\nend")));
        assertThat(rewritten.isError()).as(rewritten.content()).isFalse();
        assertThat(rewritten.content()).startsWith("已改写作品里的脚本 #1");
        assertThat(scripts.changes()).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(ScriptChange.Kind.REPLACED);
            assertThat(change.script().id()).isEqualTo(1);
            assertThat(change.previous()).as("记着被换掉的原样,供回退").isEqualTo(existing);
        });
        assertThat(scripts.all()).hasSize(2);

        ToolResult sameHat = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when [space v] key pressed\nsay [hi]\nwait (1) seconds")));
        assertThat(sameHat.content()).startsWith("已写入作品的脚本 #3").contains("已有同帽子的脚本 #2").contains("两段会一起运行");
        assertThat(scripts.changes()).hasSize(2);
        assertThat(scripts.all()).hasSize(3);

        ToolResult added = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when this sprite clicked\nnext costume")));
        assertThat(added.content()).startsWith("已写入作品的脚本 #4");
        assertThat(scripts.changes()).hasSize(3);
        assertThat(scripts.changes().get(2).kind()).isEqualTo(ScriptChange.Kind.WRITTEN);
    }

    @Test
    @DisplayName("delete_script:作品里的脚本记为删除(提交时才真删),本轮写的直接撤掉不算产物")
    void deleteScriptRecordsDeletion() {
        Script existing = Script.preexisting(1, "Cat",
                "when green flag clicked\nforever\n  move (10) steps\nend", "<xml/>", 3, List.of(), "top-1");
        Project scripts = Project.fromHarvest(project("Cat"), List.of(existing));
        TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when this sprite clicked\nnext costume")));
        assertThat(scripts.changes()).hasSize(1);

        ToolResult missing = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_SCRIPT, "{\"id\": 9}");
        assertThat(missing.isError()).isTrue();

        ToolResult mine = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_SCRIPT, "{\"id\": 2}");
        assertThat(mine.content()).startsWith("已撤掉本轮写的脚本 #2");
        assertThat(scripts.changes()).isEmpty();

        ToolResult theirs = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_SCRIPT, "{\"id\": 1}");
        assertThat(theirs.content()).startsWith("已删掉脚本 #1");
        assertThat(scripts.changes()).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(ScriptChange.Kind.DELETED);
            assertThat(change.script().code()).startsWith("when green flag clicked");
            assertThat(change.previous()).isEqualTo(existing);
        });
        assertThat(scripts.all()).isEmpty();
        assertThat(TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "replaces", 1, "code", "when green flag clicked\nmove (5) steps"))).content())
                .contains("没有编号为 #1");
    }

    @Test
    @DisplayName("同一段改写两次、改写后再删:previous 始终是动手前作品里的那段,回退一次就回到原样")
    void previousAlwaysPointsToOriginal() {
        Script existing = Script.preexisting(1, "Cat",
                "when green flag clicked\nmove (10) steps", "<xml/>", 2, List.of(), "top-1");
        Project scripts = Project.fromHarvest(project("Cat"), List.of(existing));
        TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "replaces", 1, "code", "when green flag clicked\nmove (20) steps")));
        TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "replaces", 1, "code", "when green flag clicked\nmove (30) steps")));
        assertThat(scripts.changes()).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(ScriptChange.Kind.REPLACED);
            assertThat(change.script().code()).contains("move (30) steps");
            assertThat(change.previous()).isEqualTo(existing);
        });

        TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_SCRIPT, "{\"id\": 1}");
        assertThat(scripts.changes()).singleElement().satisfies(change -> {
            assertThat(change.kind()).isEqualTo(ScriptChange.Kind.DELETED);
            assertThat(change.previous()).isEqualTo(existing);
        });
    }

    @Test
    @DisplayName("回复怎么写跟着 final_answer 走:教师配的辅导方式拼进它的说明,没配就没有那一段")
    void replyGuidanceLivesInTheFinalAnswerTool() {
        String described = TOOLS.definitions(Workspace.PROJECT, "先说结论,再说原因。\n变量名一律用中文。").stream()
                .filter(spec -> spec.name().equals(ScratchTools.FINAL_ANSWER)).findFirst().orElseThrow().description();
        assertThat(described).contains("回复的写法要求:\n先说结论,再说原因。\n变量名一律用中文。").contains("```scratchblocks");
        String plain = TOOLS.definitions(Workspace.PROJECT, "").stream()
                .filter(spec -> spec.name().equals(ScratchTools.FINAL_ANSWER)).findFirst().orElseThrow().description();
        assertThat(plain).doesNotContain("回复的写法要求").doesNotContain("像老师讲解一样");
        assertThat(TOOLS.definitions(Workspace.PROJECT, "x").stream().filter(spec -> !spec.name().equals(ScratchTools.FINAL_ANSWER)))
                .allSatisfy(spec -> assertThat(spec.description()).doesNotContain("回复的写法要求"));
    }

    @Test
    @DisplayName("讲解模式:只有读技能和 final_answer,改作品的工具一个都没有;正文里的积木图逐段编译检查,写错的退回去改,变量第一次出现就算有")
    void chatModeHasNoWorkbenchTools() {
        assertThat(TOOLS.definitions(Workspace.CHAT, null)).extracting(spec -> spec.name()).containsExactly("read_skill", "final_answer");
        assertThat(TOOLS.definitions(Workspace.PROJECT, null)).hasSize(10);
        Project none = Project.empty();
        ToolResult write = TOOLS.execute(Workspace.CHAT, none, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "小猫", "code", "when green flag clicked\nmove (10) steps")));
        assertThat(write.isError()).isTrue();
        assertThat(write.content()).contains("没有 write_script 工具");
        assertThat(none.all()).isEmpty();

        ToolResult bad = TOOLS.execute(Workspace.CHAT, none, ScratchTools.FINAL_ANSWER,
                json(Map.of("text", "用这块:\n```scratchblocks\nmove (10) step\n```\n再加一个循环:\n```scratchblocks\nrepeat (10)\n  turn right (15) degrees\n```")));
        assertThat(bad.isError()).isTrue();
        assertThat(bad.content()).contains("第 1 个积木图").contains("第 2 个积木图").contains("end");
        assertThat(none.finalAnswer()).isNull();

        ToolResult reporter = TOOLS.execute(Workspace.CHAT, none, ScratchTools.FINAL_ANSWER,
                json(Map.of("text", "读位置用:\n```scratchblocks\n(x position)\n```\n而不是\n```scratchblocks\n(x positon)\n```")));
        assertThat(reporter.isError()).isTrue();
        assertThat(reporter.content()).contains("第 2 个积木图").doesNotContain("第 1 个积木图");

        ToolResult good = TOOLS.execute(Workspace.CHAT, none, ScratchTools.FINAL_ANSWER, json(Map.of("text",
                "分数这样加:\n```scratchblocks\nchange [score v] by (1)\nsay (score)\n```\n再加一个循环:\n```scratchblocks\nrepeat (10)\n  turn right (15) degrees\nend\n```")));
        assertThat(good.isError()).as(good.content()).isFalse();
        assertThat(good.terminate()).isTrue();
    }

    @Test
    @DisplayName("一次 write_script 就是一段脚本:中间的空行不当分段;正文围栏里的空行同样不当分段")
    void blankLinesDoNotSplitAScript() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        ToolResult written = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when green flag clicked\n// 先走\nmove (10) steps\n\n// 再转\nturn right (15) degrees\n\nsay [hi]")));
        assertThat(written.isError()).as(written.content()).isFalse();
        assertThat(scripts.all().getFirst().blockCount()).isEqualTo(4);
        assertThat(scripts.all().getFirst().code()).doesNotContain("\n\n");

        ToolResult reply = TOOLS.execute(Workspace.CHAT, Project.empty(), ScratchTools.FINAL_ANSWER,
                json(Map.of("text", "这样:\n```scratchblocks\nmove (10) steps\n\nturn right (15) degrees\n```")));
        assertThat(reply.isError()).as(reply.content()).isFalse();
    }

    @Test
    @DisplayName("同一段错误代码原样重交到第 3 次:停下来问用户,本轮到此为止")
    void identicalFailuresStopTheRun() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        String broken = "when green flag clicked\nmove (10) step";
        for (int i = 1; i <= 2; i++) {
            ToolResult failed = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Cat", "code", broken)));
            assertThat(failed.isError()).isTrue();
            assertThat(failed.terminate()).isFalse();
        }
        ToolResult stopped = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Cat", "code", broken)));
        assertThat(stopped.terminate()).isTrue();
        assertThat(scripts.question()).isNotNull();
        assertThat(scripts.question().text()).contains("3 次");
        assertThat(scripts.changes()).isEmpty();
    }

    @Test
    @DisplayName("写好的脚本原样重交到第 3 次:工具结果要求循环收尾")
    void repeatedUnchangedResubmitsAskToWrapUp() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        String code = "when green flag clicked\nmove (10) steps";
        assertThat(TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Cat", "code", code))).isError()).isFalse();
        ToolResult first = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Cat", "code", code)));
        assertThat(first.content()).contains("没有变化");
        assertThat(first.wrapUp()).isFalse();
        TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Cat", "code", code)));
        ToolResult third = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Cat", "code", code)));
        assertThat(third.wrapUp()).isTrue();
        assertThat(third.content()).contains("final_answer");
        assertThat(scripts.changes()).hasSize(1);
    }

    @Test
    @DisplayName("收尾检查:缩进在循环里的 add / set 也算写过,不误报「永远是空的」")
    void finishCheckSeesIndentedWrites() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        ToolResult written = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Cat", "code",
                "when green flag clicked\ndelete all of [array v]\nrepeat (8)\n  add (pick random (1) to (9)) to [array v]\n  set [count v] to (length of [array v])\nend\nsay (item (1) of [array v])\nsay (count)")));
        assertThat(written.isError()).as(written.content()).isFalse();
        assertThat(ProgramChecks.finishProblems(scripts.all())).isEmpty();
    }

    @Test
    @DisplayName("final_answer:合规的回复登记并终止本轮;夹整段脚本或把工具调用写成文字都当错误回喂")
    void finalAnswerValidatesAndTerminates() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        String fullScript = "循环这样写:\n```scratchblocks\nwhen green flag clicked\nrepeat (10)\n  move (10) steps\nend\n```";
        ToolResult withScript = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.FINAL_ANSWER, json(Map.of("text", fullScript)));
        assertThat(withScript.isError()).as("修改模式下正文里的积木只是图,带不带帽子都放行").isFalse();
        assertThat(withScript.terminate()).isTrue();
        assertThat(scripts.finalAnswer()).isEqualTo(fullScript);

        Project again = Project.fromHarvest(project("Cat"), List.of());
        ToolResult inline = TOOLS.execute(Workspace.PROJECT, again, ScratchTools.FINAL_ANSWER,
                json(Map.of("text", "{\"name\": \"write_script\", \"arguments\": {}}")));
        assertThat(inline.isError()).as("把工具调用写成文字的不算回复").isTrue();
        ToolResult empty = TOOLS.execute(Workspace.PROJECT, again, ScratchTools.FINAL_ANSWER, json(Map.of("text", "  ")));
        assertThat(empty.isError()).isTrue();
        assertThat(again.finalAnswer()).isNull();
    }

    @Test
    @DisplayName("回复里的积木图可以调用任何角色里定义过的自定义积木;删掉本轮刚改写的脚本会被点破")
    void fencesKnowCustomBlocksAndDeleteWarns() {
        Project scripts = Project.fromHarvest(project("Cat", "Dog"), List.of());
        Workspace workspace = Workspace.PROJECT;
        TOOLS.execute(workspace, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Cat", "code", "define draw array\nmove (10) steps")));
        TOOLS.execute(workspace, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Cat", "code", "when green flag clicked\ndraw array")));
        ToolResult fromDog = TOOLS.execute(workspace, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Dog", "code", "when green flag clicked\ndraw array")));
        assertThat(fromDog.isError()).isTrue();
        assertThat(fromDog.content()).contains("角色 Cat 里的自定义积木");
        ToolResult reply = TOOLS.execute(workspace, scripts, ScratchTools.FINAL_ANSWER,
                json(Map.of("text", "每轮画一次:\n```scratchblocks\nrepeat (8)\n  draw array\nend\n```")));
        assertThat(reply.isError()).as(reply.content()).isFalse();

        Project again = Project.fromHarvest(project("Cat", "Dog"), List.of(Script.preexisting(1, "Cat", "define draw array\nmove (10) steps", "<xml/>", 2, List.of("draw array"), "top-1")));
        TOOLS.execute(workspace, again, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Cat", "code", "define draw array\nmove (20) steps", "replaces", 1)));
        ToolResult deleted = TOOLS.execute(workspace, again, ScratchTools.DELETE_SCRIPT, json(Map.of("id", 1)));
        assertThat(deleted.content()).contains("本轮刚改写过的");
    }

    @Test
    @DisplayName("write_script 声明私有变量:建在角色上,不进全局;和已有全局变量同名的拒绝;声明了却没用到的点破")
    void localVariablesAreDeclaredPerScript() {
        Project scripts = Project.fromHarvest(project("Apple"), List.of());
        ToolResult clone = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Apple", "code", "when I start as a clone\nset [speed v] to (pick random (2) to (6))\nset [score v] to (0)",
                        "localVariables", List.of("speed", "unused"))));
        assertThat(clone.isError()).as(clone.content()).isFalse();
        assertThat(clone.content()).contains("新建变量:score").contains("新建 Apple 的私有变量:speed").contains("声明的私有变量 unused 这段脚本里没有出现");
        assertThat(scripts.sprite("Apple").localVariables()).containsExactly("speed");
        assertThat(scripts.globalVariables()).containsExactly("score");
        assertThat(scripts.all().getFirst().localVariables()).containsExactly("speed");
        ToolResult clash = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Apple", "code", "when green flag clicked\nset [score v] to (1)", "localVariables", List.of("score"))));
        assertThat(clash.isError()).isTrue();
        assertThat(clash.content()).contains("已经是全局变量");
    }

    @Test
    @DisplayName("delete_variable / delete_list:还有脚本用着就拒绝并列出脚本;没人用就删;delete_sprite 连同脚本,原有角色注明恢复不了")
    void deleteToolsRespectUsersAndRecordChanges() {
        HarvestPayload harvest = new HarvestPayload(List.of(
                new HarvestPayload.SpriteContext("Cat", false, List.of("costume1"), List.of(), List.of("speed"), List.of()),
                new HarvestPayload.SpriteContext("Stage", true, List.of("backdrop1"), List.of(), List.of(), List.of())),
                List.of("score", "old"), List.of("items"), List.of(), Map.of(), "Cat", Map.of());
        Project scripts = Project.fromHarvest(harvest, List.of(
                Script.preexisting(1, "Cat", "when green flag clicked\nset [score v] to (0)", "<xml><block type=\"data_setvariableto\"><field name=\"VARIABLE\">score</field></block></xml>", 1, List.of(), "top-1")));
        ToolResult inUse = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_VARIABLE, json(Map.of("name", "score")));
        assertThat(inUse.isError()).isTrue();
        assertThat(inUse.content()).contains("还在被脚本用着").contains("#1(角色 Cat)");
        assertThat(TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_VARIABLE, json(Map.of("name", "nope"))).isError()).isTrue();
        assertThat(TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_VARIABLE, json(Map.of("name", "old"))).content()).isEqualTo("已删掉全局的变量 old");
        assertThat(TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_LIST, json(Map.of("name", "items"))).content()).isEqualTo("已删掉全局的列表 items");
        assertThat(scripts.diff().deletedVariables()).extracting(VariableChange::name).containsExactly("old", "items");

        ToolResult gone = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_SPRITE, json(Map.of("name", "Cat")));
        assertThat(gone.content()).contains("已删掉角色 Cat,连同它的 1 段脚本").contains("作品里原有的角色");
        assertThat(scripts.sprite("Cat")).isNull();
        assertThat(scripts.diff().deletedSprites()).singleElement().extracting(SpriteChange::preexisting).isEqualTo(true);
        assertThat(scripts.diff().deletedVariables()).extracting(VariableChange::name).containsExactly("old", "items", "speed");
        assertThat(TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_SPRITE, json(Map.of("name", "Stage"))).isError()).isTrue();
    }

    @Test
    @DisplayName("final_answer 前的收尾检查:克隆没克隆体脚本、broadcast 紧跟 stop all,第一次 final_answer 打回,第二次放行")
    void finalAnswerRunsFinishChecksOnce() {
        Project scripts = Project.fromHarvest(project("Apple", "Bowl"), List.of());
        TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Apple", "code", "when green flag clicked\nforever\n  create clone of [myself v]\n  wait (1) seconds\nend")));
        ToolResult written = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Bowl", "code", "when green flag clicked\nset [lives v] to (3)\nwait until <(lives) < (1)>\nbroadcast [game over v]\nstop [all v]")));
        assertThat(written.isError()).as(written.content()).isFalse();

        ToolResult first = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.FINAL_ANSWER, json(Map.of("text", "做好了")));
        assertThat(first.isError()).isTrue();
        assertThat(first.content()).contains("角色 Apple 会被 create clone").contains("broadcast 后紧跟 stop [all v]");
        assertThat(scripts.finalAnswer()).isNull();

        ToolResult second = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.FINAL_ANSWER, json(Map.of("text", "做好了")));
        assertThat(second.terminate()).isTrue();
        assertThat(scripts.finalAnswer()).isEqualTo("做好了");
    }

    @Test
    @DisplayName("收尾检查:同角色两段完全相同的脚本(作品里已有的)报重复")
    void finishChecksFlagDuplicates() {
        List<Script> scripts = List.of(
                Script.preexisting(1, "Cat", "when [space v] key pressed\nsay [hi]", "", 2, List.of(), "b1"),
                Script.preexisting(2, "Cat", "when [space v] key pressed\n// 同一段\nsay [hi]", "", 2, List.of(), "b2"));
        assertThat(ProgramChecks.finishProblems(scripts)).singleElement().asString().contains("#1 和 #2 完全相同");
    }

    @Test
    @DisplayName("收尾检查:克隆体脚本齐全、broadcast and wait、不同脚本,不报问题(事实从编译出的 XML 读)")
    void finishChecksPassCleanPrograms() {
        Project scripts = Project.fromHarvest(project("Cat", "Apple", "Bowl"), List.of());
        for (String[] script : List.of(
                new String[] {"Apple", "when green flag clicked\nforever\n  create clone of [myself v]\nend"},
                new String[] {"Apple", "when I start as a clone\nshow\nrepeat until <touching [Bowl v]?>\n  change y by (-4)\nend\ndelete this clone"},
                new String[] {"Bowl", "when green flag clicked\nbroadcast [game over v] and wait\nstop [all v]"})) {
            ToolResult written = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                    json(Map.of("sprite", script[0], "code", script[1])));
            assertThat(written.isError()).as(written.content()).isFalse();
        }
        assertThat(ProgramChecks.finishProblems(scripts.all())).isEmpty();
    }

    @Test
    @DisplayName("编译错误的行号按模型自己那段文本算:中间的空行去掉了,行号仍指向它写的那一行")
    void errorLineNumbersFollowTheModelsText() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        ToolResult failed = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when green flag clicked\nmove (10) steps\n\n\nmove (10) step")));
        assertThat(failed.isError()).isTrue();
        assertThat(failed.content()).contains("第 5 行").doesNotContain("第 3 行");
    }

    @Test
    @DisplayName("create_sprite 登记的新角色造型、声音同作品里的第一个角色(编辑器建角色就是复制它),下拉按此校验")
    void createdSpriteCopiesTheTemplateCostumes() {
        List<HarvestPayload.SpriteContext> contexts = List.of(
                new HarvestPayload.SpriteContext("小猫", false, List.of("造型1", "造型2"), List.of("喵"), List.of(), List.of()),
                new HarvestPayload.SpriteContext("Stage", true, List.of("背景1"), List.of(), List.of(), List.of()));
        HarvestPayload project = new HarvestPayload(contexts, List.of(), List.of(), List.of(), Map.of(), "小猫", Map.of());
        Project scripts = Project.fromHarvest(project, List.of());
        assertThat(TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.CREATE_SPRITE, json(Map.of("name", "苹果"))).content())
                .contains("造型和声音同 小猫");
        ToolResult ok = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "苹果", "code", "when green flag clicked\nswitch costume to [造型2 v]\nstart sound [喵 v]")));
        assertThat(ok.isError()).as(ok.content()).isFalse();
        ToolResult bad = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "苹果", "code", "when green flag clicked\nswitch costume to [costume2 v]")));
        assertThat(bad.isError()).isTrue();
        assertThat(bad.content()).contains("没有叫 \"costume2\" 的造型");
    }

    @Test
    @DisplayName("读到没创建的名字:自动创建并提醒,和已有名字只差一两个字符的提醒里点明;收尾时没被赋值的打回")
    void readingUnknownNamesCreatesUnlessTypo() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when green flag clicked\nset [score v] to (0)")));
        ToolResult typo = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when [space v] key pressed\nsay (scroe)")));
        assertThat(typo.isError()).as(typo.content()).isFalse();
        assertThat(typo.content()).contains("\"scroe\" 还没有任何脚本创建它").contains("和已有的 \"score\" 只差一两个字符");
        scripts.delete(scripts.find(2));

        ToolResult created = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when [space v] key pressed\nsay (lives)\nsay (item (1) of [names v])")));
        assertThat(created.isError()).as(created.content()).isFalse();
        assertThat(created.content()).contains("提醒").contains("lives").contains("names");

        ToolResult reply = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.FINAL_ANSWER, json(Map.of("text", "做好了")));
        assertThat(reply.isError()).isTrue();
        assertThat(reply.content()).contains("变量 \"lives\" 只被读取").contains("列表 \"names\" 只被读取");
    }

    @Test
    @DisplayName("同名变量既是全局的又是某角色私有的:不指明就报有几份;指明角色只删那份,Stage 指全局;在用与否按那一份算")
    void deletingOneOfSeveralSameNamedVariables() {
        HarvestPayload harvest = new HarvestPayload(List.of(
                new HarvestPayload.SpriteContext("Cat", false, List.of("costume1"), List.of(), List.of("speed"), List.of()),
                new HarvestPayload.SpriteContext("Dog", false, List.of("costume1"), List.of(), List.of(), List.of()),
                new HarvestPayload.SpriteContext("Stage", true, List.of("backdrop1"), List.of(), List.of(), List.of())),
                List.of("speed"), List.of(), List.of(), Map.of(), "Cat", Map.of());
        String setSpeed = "<xml><block type=\"data_setvariableto\"><field name=\"VARIABLE\">speed</field></block></xml>";
        Project scripts = Project.fromHarvest(harvest, List.of(
                Script.preexisting(1, "Dog", "when green flag clicked\nset [speed v] to (0)", setSpeed, 1, List.of(), "top-1")));
        ToolResult ambiguous = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_VARIABLE, json(Map.of("name", "speed")));
        assertThat(ambiguous.isError()).isTrue();
        assertThat(ambiguous.content()).contains("有 2 份").contains("全局").contains("角色 Cat 私有").contains("Stage 指全局");
        ToolResult wrongSprite = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_VARIABLE, json(Map.of("name", "speed", "sprite", "Dog")));
        assertThat(wrongSprite.isError()).isTrue();
        assertThat(wrongSprite.content()).contains("角色 Dog 私有没有变量 \"speed\"");
        // Dog 的脚本用的是全局那份:全局的删不掉,Cat 私有的能删
        ToolResult globalInUse = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_VARIABLE, json(Map.of("name", "speed", "sprite", "Stage")));
        assertThat(globalInUse.isError()).isTrue();
        assertThat(globalInUse.content()).contains("全局的变量 \"speed\" 还在被脚本用着").contains("#1(角色 Dog)");
        ToolResult ok = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_VARIABLE, json(Map.of("name", "speed", "sprite", "Cat")));
        assertThat(ok.isError()).as(ok.content()).isFalse();
        assertThat(ok.content()).isEqualTo("已删掉角色 Cat 私有的变量 speed");
        assertThat(scripts.sprite("Cat").localVariables()).isEmpty();
        assertThat(scripts.globalVariables()).contains("speed");
        assertThat(scripts.diff().deletedVariables()).singleElement().satisfies(change -> assertThat(change.sprite()).isEqualTo("Cat"));
        // 只剩一份了:不用指明
        assertThat(scripts.deleteVariable("speed", false)).isFalse();
        assertThat(TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_VARIABLE, json(Map.of("name", "speed"))).content()).contains("还在被脚本用着");
    }

    @Test
    @DisplayName("收尾阶段(步数用完):final_answer 不再被打回,收尾检查出的问题附在回复末尾,如实告诉用户")
    void wrapUpAcceptsFinalAnswerAndAppendsCheckResults() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when green flag clicked\nsay (lives)")));
        ToolResult rejected = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.FINAL_ANSWER, json(Map.of("text", "做好了")));
        assertThat(rejected.isError()).isTrue();

        Project again = Project.fromHarvest(project("Cat"), List.of());
        TOOLS.execute(Workspace.PROJECT, again, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when green flag clicked\nsay (lives)")));
        again.enterWrapUp();
        ToolResult accepted = TOOLS.execute(Workspace.PROJECT, again, ScratchTools.FINAL_ANSWER, json(Map.of("text", "做好了")));
        assertThat(accepted.isError()).as(accepted.content()).isFalse();
        assertThat(accepted.content()).startsWith("做好了\n\n" + ScratchTools.WRAP_UP_CHECK_HEADING + "\n- ").contains("变量 \"lives\" 只被读取");
        assertThat(again.finalAnswer()).isEqualTo(accepted.content());
    }

    @Test
    @DisplayName("自定义积木以回复里的 define 为准:同一条回复里先 define 再调用能过;之前回复定义过的带进本轮也能过;从没定义过的打回")
    void proceduresFromEarlierRepliesCarryOver() {
        List<String> replies = List.of(
                "先定义一个积木:\n```scratchblocks\ndefine jump (height)\nchange y by (height)\n```\n再用:\n```scratchblocks\nwhen green flag clicked\njump (50)\n```",
                "```scratchblocks\ndefine broken (\n```");
        var procedures = TOOLS.proceduresIn(replies);
        assertThat(procedures).extracting(p -> p.proccode()).containsExactly("jump %s");
        ToolResult sameReply = TOOLS.execute(Workspace.CHAT, Project.empty(), ScratchTools.FINAL_ANSWER, json(Map.of("text",
                "先定义:\n```scratchblocks\ndefine wave (times)\nrepeat (times)\n  turn right (15) degrees\nend\n```\n再用:\n```scratchblocks\nwave (3)\n```")));
        assertThat(sameReply.isError()).as(sameReply.content()).isFalse();
        Project draft = Project.empty().inheritProcedures(procedures);
        ToolResult reply = TOOLS.execute(Workspace.CHAT, draft, ScratchTools.FINAL_ANSWER,
                json(Map.of("text", "这样调用:\n```scratchblocks\nwhen [space v] key pressed\njump (30)\n```")));
        assertThat(reply.isError()).as(reply.content()).isFalse();
        ToolResult unknown = TOOLS.execute(Workspace.CHAT, Project.empty(), ScratchTools.FINAL_ANSWER,
                json(Map.of("text", "```scratchblocks\nwhen [space v] key pressed\njump (30)\n```")));
        assertThat(unknown.isError()).isTrue();
    }

    @Test
    @DisplayName("一个角色的私有列表在别的角色里读不到:报它是谁的私有列表,不当拼错、也不另建一个")
    void privateListsOfAnotherSpriteAreReportedAsSuch() {
        Project scripts = Project.fromHarvest(project("SnakeHead", "SnakeBody"), List.of());
        ToolResult head = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "SnakeHead",
                "code", "when green flag clicked\nadd (x position) to [snake x positions v]\nadd (y position) to [snake y positions v]",
                "localLists", List.of("snake x positions", "snake y positions"))));
        assertThat(head.isError()).as(head.content()).isFalse();
        ToolResult body = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "SnakeBody",
                "code", "when green flag clicked\ngo to x: (item (1) of [snake x positions v]) y: (item (1) of [snake y positions v])")));
        assertThat(body.isError()).isTrue();
        assertThat(body.content()).contains("列表 \"snake x positions\" 是角色 SnakeHead 私有的")
                .contains("列表 \"snake y positions\" 是角色 SnakeHead 私有的").doesNotContain("已有的是");
        assertThat(scripts.globalLists()).isEmpty();
    }

    @Test
    @DisplayName("封口积木后面接积木是错误,嵌在 if / forever 里也一样(编辑器接不上,插入会失败)")
    void capBlocksEndTheChainEverywhere() {
        Project scripts = Project.fromHarvest(project("Cat", "Pipe"), List.of());
        ToolResult nested = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Pipe", "code", "when I start as a clone\nforever\n  if <touching [edge v]?> then\n    delete this clone\n    stop [this script v]\n  end\nend")));
        assertThat(nested.isError()).isTrue();
        assertThat(nested.content()).contains("第 4 行的 delete 已经结束了脚本");

        ToolResult afterForever = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Pipe", "code", "when green flag clicked\nforever\n  move (1) steps\nend\nsay [done]")));
        assertThat(afterForever.isError()).isTrue();
        assertThat(afterForever.content()).contains("第 2 行的 forever 已经结束了脚本");

        ToolResult ok = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Pipe", "code", "when green flag clicked\nstop [other scripts in sprite v]\nsay [done]")));
        assertThat(ok.isError()).as(ok.content()).isFalse();
    }

    @Test
    @DisplayName("撤掉后原样重写:点破它,免得模型删了写、写了删打转")
    void rewritingWithdrawnScriptIsCalledOut() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        String code = "when green flag clicked\nmove (10) steps";
        TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Cat", "code", code)));
        TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.DELETE_SCRIPT, json(Map.of("id", 1)));
        ToolResult again = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT, json(Map.of("sprite", "Cat", "code", code)));
        assertThat(again.isError()).isFalse();
        assertThat(again.content()).contains("刚用 delete_script 删掉的一模一样");
    }

    @Test
    @DisplayName("形状规则:帽子处自动另起一段,嵌在 if 里的帽子截断 if 而报错,语句不能进槽")
    void shapeRulesApplyEverywhere() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        ToolResult hatAfter = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when green flag clicked\nmove (10) steps\nwhen [space v] key pressed\nsay [hi]")));
        assertThat(hatAfter.isError()).as("帽子处解析器自动另起一段:两段都写入").isFalse();
        assertThat(hatAfter.content()).contains("4 个积木");

        ToolResult hatInside = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when green flag clicked\nif <touching [edge v]?> then\n  when [space v] key pressed\n  say [hi]\nend")));
        assertThat(hatInside.isError()).as("if 里冒出帽子:if 被截断,报没有对应的 end").isTrue();
        assertThat(hatInside.content()).contains("没有对应的 end");

        ToolResult statementInSlot = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Cat", "code", "when green flag clicked\nsay (move (10) steps)")));
        assertThat(statementInSlot.isError()).isTrue();
        assertThat(statementInSlot.content()).contains("是一条语句,不能放进槽里");
    }

    @Test
    @DisplayName("ask_user:记下问题与选项并终止本轮")
    void askUserTerminates() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        ToolResult result = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.ASK_USER,
                json(Map.of("question", "用哪个角色?", "options", List.of("Cat", "新建一个"))));
        assertThat(result.terminate()).isTrue();
        assertThat(scripts.question().text()).isEqualTo("用哪个角色?");
        assertThat(scripts.question().options()).containsExactly("Cat", "新建一个");
    }

    @Test
    @DisplayName("create_sprite:登记新角色后,本轮后续 write_script 认得这个角色")
    void createSpriteThenWrite() {
        Project scripts = Project.fromHarvest(project("Cat"), List.of());
        HarvestPayload project = project("Cat");
        ToolResult created = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.CREATE_SPRITE, json(Map.of("name", "Apple")));
        assertThat(created.isError()).isFalse();
        ToolResult written = TOOLS.execute(Workspace.PROJECT, scripts, ScratchTools.WRITE_SCRIPT,
                json(Map.of("sprite", "Apple", "code", "when green flag clicked\nhide")));
        assertThat(written.isError()).as(written.content()).isFalse();
    }
}
