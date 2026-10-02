package cn.utcy.teaching.blockcoding.application.agent;

import cn.utcy.teaching.blockcoding.engine.ProcedureXml;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Procedure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ProgramChecks {
    private ProgramChecks() {
    }

    static List<String> finishProblems(List<Script> scripts) {
        List<String> problems = new ArrayList<>();
        Map<String, List<Script>> bySprite = new LinkedHashMap<>();
        Map<Script, ScriptFacts> facts = new LinkedHashMap<>();
        for (Script script : scripts) {
            bySprite.computeIfAbsent(script.sprite(), k -> new ArrayList<>()).add(script);
            facts.put(script, ScriptFacts.of(script.xml(), script.sprite()));
        }
        Set<String> cloneTargets = new LinkedHashSet<>();
        Set<String> writtenVariables = new LinkedHashSet<>();
        Set<String> writtenLists = new LinkedHashSet<>();
        for (Script script : scripts) {
            ScriptFacts fact = facts.get(script);
            cloneTargets.addAll(fact.cloneTargets());
            writtenVariables.addAll(fact.writtenVariables());
            writtenLists.addAll(fact.writtenLists());
            if (fact.broadcastThenStopAll()) {
                problems.add("脚本 #" + script.id() + "(角色 " + script.sprite() + ")里 broadcast 后紧跟 stop [all v]:"
                        + "接收方还没开始执行就被停掉");
            }
        }
        for (String sprite : cloneTargets) {
            boolean hasCloneHat = bySprite.getOrDefault(sprite, List.of()).stream().anyMatch(script -> facts.get(script).startsAsClone());
            if (!hasCloneHat) {
                problems.add("角色 " + sprite + " 会被 create clone,却没有 when I start as a clone 脚本:克隆体出来后什么都不做");
            }
        }
        bySprite.forEach((sprite, list) -> {
            boolean cloneHat = list.stream().anyMatch(script -> facts.get(script).startsAsClone());
            if (cloneHat && !cloneTargets.contains(sprite)) {
                problems.add("角色 " + sprite + " 有 when I start as a clone 脚本,却没有任何脚本 create clone of 它:克隆体脚本永远不会运行");
            }
        });
        Set<String> unwrittenVariables = new LinkedHashSet<>();
        Set<String> unwrittenLists = new LinkedHashSet<>();
        for (Script script : scripts) {
            script.variables().stream().filter(name -> !writtenVariables.contains(name)).forEach(unwrittenVariables::add);
            script.localVariables().stream().filter(name -> !writtenVariables.contains(name)).forEach(unwrittenVariables::add);
            script.lists().stream().filter(name -> !writtenLists.contains(name)).forEach(unwrittenLists::add);
            script.localLists().stream().filter(name -> !writtenLists.contains(name)).forEach(unwrittenLists::add);
        }
        unwrittenVariables.forEach(name -> problems.add("变量 \"" + name + "\" 只被读取,没有任何脚本用 set / change 给它赋值,永远是 0"));
        unwrittenLists.forEach(name -> problems.add("列表 \"" + name + "\" 只被读取,没有任何脚本用 add / insert / replace 往里放内容,永远是空的"));
        bySprite.forEach((sprite, list) -> {
            Set<String> defined = new LinkedHashSet<>();
            list.forEach(script -> ProcedureXml.definitions(script.xml()).stream().map(Procedure::proccode).forEach(defined::add));
            for (Script script : list) {
                for (String proccode : facts.get(script).calledProcedures()) {
                    if (!defined.contains(proccode)) {
                        problems.add("角色 " + sprite + " 的脚本 #" + script.id() + " 调用了自定义积木 \"" + proccode.replace("%s", "()").replace("%b", "<>")
                                + "\",但这个角色里没有它的 define 脚本(自定义积木不跨角色)");
                    }
                }
            }
        });
        bySprite.forEach((sprite, list) -> {
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    if (ScriptText.sameLines(list.get(i).code(), list.get(j).code())) {
                        problems.add("角色 " + sprite + " 的脚本 #" + list.get(i).id() + " 和 #" + list.get(j).id()
                                + " 完全相同,会一起运行");
                    }
                }
            }
        });
        return problems;
    }
}
