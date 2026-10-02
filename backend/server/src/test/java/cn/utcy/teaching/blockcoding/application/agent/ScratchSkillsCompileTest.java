package cn.utcy.teaching.blockcoding.application.agent;

import cn.utcy.teaching.blockcoding.application.agent.SkillCatalog;
import cn.utcy.teaching.blockcoding.engine.BlockTable;
import cn.utcy.teaching.blockcoding.engine.SbEngine;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Known;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Procedure;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Result;
import java.util.LinkedHashSet;
import java.util.Set;
import cn.utcy.teaching.blockcoding.engine.SbParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 技能是模型的正例来源:每份技能的「片段」节里每个代码块都必须编译通过——写法表里的行不单独编译
 * (它们是带占位槽的模板),片段是完整脚本。片段里读到的变量视为已存在。
 */
class ScratchSkillsCompileTest {

    private static final Pattern FENCE = Pattern.compile("```\\n([\\s\\S]*?)```");

    @TestFactory
    List<DynamicTest> everySnippetCompiles() {
        ObjectMapper mapper = new ObjectMapper();
        SbEngine engine = new SbEngine(new ScriptCompiler(new SbParser(mapper), new BlockTable(mapper)));
        SkillCatalog skills = new SkillCatalog("blockcoding/skills");
        List<DynamicTest> tests = new ArrayList<>();
        for (SkillCatalog.Skill skill : skills.all()) {
            int snippetStart = skill.content().indexOf("## 片段");
            if (snippetStart < 0) {
                snippetStart = skill.content().indexOf("## 1.");
            }
            assertThat(snippetStart).as("技能 %s 缺少片段节", skill.name()).isGreaterThanOrEqualTo(0);
            String snippets = skill.content().substring(snippetStart);
            Matcher matcher = FENCE.matcher(snippets);
            int index = 0;
            while (matcher.find()) {
                String code = matcher.group(1);
                int number = ++index;
                tests.add(DynamicTest.dynamicTest(skill.name() + " #" + number + " " + code.lines().findFirst().orElse(""), () -> {
                    Result result = engine.compile("```\n" + code + "```", knownAll(skill.content(), code));
                    assertThat(result.ok())
                            .as("技能 %s 片段 #%d 编译失败:%s\n%s", skill.name(), number, errors(result), code)
                            .isTrue();
                }));
            }
            assertThat(index).as("技能 %s 没有片段", skill.name()).isPositive();
        }
        return tests;
    }

    /**
     * 片段里读到的名字视为作品里已存在:圆括号里的词是变量;只在列表积木语境(of / to / contains / show list)
     * 里出现的 [名 v] 是列表;同一技能里其他片段 define 的自定义积木也算已定义(调用片段单独编译要能过)。
     */
    private static Known knownAll(String skillContent, String code) {
        Set<String> variables = new LinkedHashSet<>();
        Set<String> lists = new LinkedHashSet<>();
        List<Procedure> procs = new ArrayList<>();
        Matcher reads = Pattern.compile("\\(([A-Za-z_][\\w ]*)\\)").matcher(code);
        while (reads.find()) {
            variables.add(reads.group(1));
        }
        Matcher listNames = Pattern.compile("(?:of|to|at \\(\\d+\\) of|all of) \\[([^\\]]+?) v\\]|\\[([^\\]]+?) v\\] contains|(?:show|hide) list \\[([^\\]]+?) v\\]").matcher(code);
        while (listNames.find()) {
            for (int group = 1; group <= 3; group++) {
                if (listNames.group(group) != null) {
                    lists.add(listNames.group(group));
                }
            }
        }
        Matcher defines = Pattern.compile("^define (.+)$", Pattern.MULTILINE).matcher(skillContent);
        while (defines.find()) {
            String signature = defines.group(1).strip();
            List<String> names = new ArrayList<>();
            List<String> ids = new ArrayList<>();
            Matcher params = Pattern.compile("\\(([^()]+)\\)|<([^<>]+)>").matcher(signature);
            StringBuilder proccode = new StringBuilder();
            int last = 0;
            int index = 0;
            while (params.find()) {
                proccode.append(signature, last, params.start());
                boolean bool = params.group(2) != null;
                proccode.append(bool ? "%b" : "%s");
                names.add(bool ? params.group(2) : params.group(1));
                ids.add("arg" + (index++));
                last = params.end();
            }
            proccode.append(signature.substring(last));
            procs.add(new Procedure(proccode.toString(), names, ids));
        }
        return new Known(variables, lists, procs, null);
    }

    private static String errors(Result result) {
        StringBuilder sb = new StringBuilder();
        result.errors().forEach(error -> sb.append("L").append(error.line()).append(": ").append(error.message()).append('\n'));
        return sb.toString();
    }
}
