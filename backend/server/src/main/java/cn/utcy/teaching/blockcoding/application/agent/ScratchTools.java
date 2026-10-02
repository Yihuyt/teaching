package cn.utcy.teaching.blockcoding.application.agent;

import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.ai.agent.ChatAgentLoop.ToolResult;
import cn.utcy.teaching.blockcoding.application.agent.SkillCatalog;
import cn.utcy.teaching.blockcoding.application.agent.ScriptText.Lines;
import cn.utcy.teaching.blockcoding.engine.ProcedureXml;
import cn.utcy.teaching.blockcoding.engine.SbEngine;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Diagnostic;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Known;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Procedure;
import cn.utcy.teaching.blockcoding.engine.ScriptCompiler.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 积木助手的工具执行器。工具只改工作台({@link Project}),不碰编辑器;两种模式哪里不一样由 {@link Workspace} 定。
 * 编译失败是模型可读的结构化错误(isError),不抛异常。给模型的话只写事实与规则,不写死"怎么改"。
 */
public final class ScratchTools {
    public static final String READ_SKILL = "read_skill";
    public static final String WRITE_SCRIPT = "write_script";
    public static final String LIST_PROJECT = "list_project";
    public static final String ASK_USER = "ask_user";
    public static final String CREATE_SPRITE = "create_sprite";
    public static final String DELETE_SCRIPT = "delete_script";
    public static final String DELETE_SPRITE = "delete_sprite";
    public static final String DELETE_VARIABLE = "delete_variable";
    public static final String DELETE_LIST = "delete_list";
    public static final String FINAL_ANSWER = "final_answer";

    static final Set<String> ALL = Set.of(READ_SKILL, WRITE_SCRIPT, LIST_PROJECT, ASK_USER, CREATE_SPRITE, DELETE_SCRIPT,
            DELETE_SPRITE, DELETE_VARIABLE, DELETE_LIST, FINAL_ANSWER);
    /** 写者工具在循环里整批串行:后写的脚本要能读到先写的脚本创建的变量;建、删都要按序落到编辑器 */
    public static final Set<String> WRITERS = Set.of(WRITE_SCRIPT, CREATE_SPRITE, DELETE_SCRIPT, DELETE_SPRITE, DELETE_VARIABLE, DELETE_LIST);
    public static final Set<String> FINISHERS = Set.of(FINAL_ANSWER, ASK_USER);
    public static final String WRAP_UP_CHECK_HEADING = "助手的步数用完了,以下问题还没来得及处理(程序能跑但会不对):";

    private static final int RESULT_CAP = 8 * 1024;
    /** 同一段代码原样重交、错误也一样到第几次就停下来问用户;写好的脚本原样重交到第几次就让循环收尾 */
    static final int STUCK_RESUBMITS = 3;
    /** 模型偶尔把工具调用当文字打出来:<tools>{"name": …}</tools> 或裸的 {"name": "read_skill", "arguments": …} */
    static final Pattern INLINE_TOOL_CALL = Pattern.compile(
            "<tools?>|<tool_call>|<tool_code>|\\{\\s*\"name\"\\s*:\\s*\"(read_skill|write_script|list_project|create_sprite|delete_script|delete_sprite|delete_variable|delete_list|ask_user|final_answer)\""
                    + "|\\b(read_skill|write_script|list_project|create_sprite|delete_script|delete_sprite|delete_variable|delete_list|ask_user|final_answer)\\(\\s*\\{");
    private static final Pattern BLOCK_FENCE = Pattern.compile("```[^\\n]*\\n([\\s\\S]*?)```");

    private final SbEngine engine;
    private final SkillCatalog skills;
    private final ObjectMapper objectMapper;

    public ScratchTools(SbEngine engine, SkillCatalog skills, ObjectMapper objectMapper) {
        this.engine = engine;
        this.skills = skills;
        this.objectMapper = objectMapper;
    }

    public List<ToolSpecification> definitions(Workspace workspace, String replyGuidance) {
        return ToolDefinitions.of(workspace, replyGuidance);
    }

    public ToolResult execute(Workspace workspace, Project project, String name, String argumentsJson) {
        if (!workspace.tools().contains(name)) {
            return ToolResult.error(ALL.contains(name)
                    ? "这个模式下没有 " + name + " 工具;可用:" + String.join("、", workspace.tools())
                    : "未知工具:" + name);
        }
        JsonNode args;
        try {
            args = objectMapper.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (Exception exception) {
            return ToolResult.error("参数不是合法 JSON");
        }
        return switch (name) {
            case READ_SKILL -> readSkill(args.path("name").asText(""));
            case WRITE_SCRIPT -> writeScript(project, args.path("sprite").asText(""), args.path("code").asText(""),
                    args.hasNonNull("replaces") ? args.path("replaces").asInt() : null,
                    strings(args.path("localVariables")), strings(args.path("localLists")));
            case LIST_PROJECT -> ToolResult.of(project.summary("作品", "作品里已有"), List.of());
            case ASK_USER -> askUser(project, args);
            case CREATE_SPRITE -> createSprite(project, args.path("name").asText(""));
            case DELETE_SCRIPT -> deleteScript(project, args.path("id").asInt(-1));
            case DELETE_SPRITE -> deleteSprite(project, args.path("name").asText(""));
            case DELETE_VARIABLE -> deleteVariable(project, args.path("name").asText(""), args.path("sprite").asText(""), false);
            case DELETE_LIST -> deleteVariable(project, args.path("name").asText(""), args.path("sprite").asText(""), true);
            case FINAL_ANSWER -> finalAnswer(project, args.path("text").asText(""));
            default -> ToolResult.error("未知工具:" + name);
        };
    }

    private static Set<String> strings(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        if (array != null && array.isArray()) {
            array.forEach(item -> {
                String value = item.asText("").strip();
                if (!value.isEmpty()) {
                    out.add(value);
                }
            });
        }
        return out;
    }

    private ToolResult readSkill(String name) {
        SkillCatalog.Skill skill = skills.find(name);
        if (skill == null) {
            return ToolResult.error("没有名为 \"" + name + "\" 的技能;可用技能:\n" + skills.roster());
        }
        return ToolResult.of("# " + skill.title() + "\n\n" + skill.content(), List.of());
    }

    private ToolResult writeScript(Project project, String sprite, String code, Integer replaces,
                                   Set<String> localVariables, Set<String> localLists) {
        if (sprite.isBlank()) {
            return ToolResult.error("缺少 sprite:请写明脚本属于哪个角色");
        }
        Sprite target = project.sprite(sprite);
        if (target == null) {
            return ToolResult.error("作品里没有叫 \"" + sprite + "\" 的角色;现有:" + spriteNames(project));
        }
        if (code.isBlank()) {
            return ToolResult.error("code 是空的");
        }
        // 声明为私有的名字不能已经是别处的变量:同名的全局变量和私有变量在编辑器里会撞
        for (String name : localVariables) {
            if (project.globalVariables().contains(name)) {
                return ToolResult.error("变量 \"" + name + "\" 已经是全局变量,不能再声明为 " + target.name() + " 的私有变量");
            }
        }
        for (String name : localLists) {
            if (project.globalLists().contains(name)) {
                return ToolResult.error("列表 \"" + name + "\" 已经是全局列表,不能再声明为 " + target.name() + " 的私有列表");
            }
        }
        // 一次 write_script 就是一段脚本:模型爱用空行分段落,而 scratchblocks 把空行当成另一段脚本的开始。
        // 去掉空行编译,报错的行号换算回模型自己那段文本的行号
        Lines lines = Lines.withoutBlank(ScriptText.stripSpriteHeader(code.strip()));
        String body = lines.text();
        String firstLine = body.lines().findFirst().orElse("").strip().toLowerCase();
        if (!firstLine.startsWith("when ") && !firstLine.startsWith("define ")) {
            return ToolResult.error("脚本第 1 行不是帽子积木(when … / define …):没有帽子的脚本永远不会运行");
        }
        Known known = project.knownFor(target.name(), true).withDeclaredLocals(localVariables, localLists);
        Result result = engine.compile(body, known);
        if (!result.ok()) {
            int times = project.failedAgain(target.name(), body);
            if (times >= STUCK_RESUBMITS) {
                // 同一段原样交了三次、错也一样:模型已经改不动了,停下来交给用户,别再烧轮数
                String question = "同一段脚本我改了 " + times + " 次都是同样的编译错误,先停下来。"
                        + "你可以把要求拆小一点再说一次,或者告诉我具体想要什么效果。";
                project.ask(new Question(question, List.of()));
                return ToolResult.terminal(question);
            }
            StringBuilder sb = new StringBuilder(times >= 2
                    ? "这段代码和上次提交的一样,错误也一样(第 " + times + " 次)。不要原样重交:对照技能里的片段重写这一段,或拆成两段更短的脚本。\n"
                    : "编译未通过,请按错误修改后重新 write_script:\n");
            sb.append(describeErrors(result, lines));
            return ToolResult.error(Text.truncate(sb.toString(), RESULT_CAP));
        }
        ScriptFacts facts = ScriptFacts.of(result.xml(), target.name());
        if (target.isStage()) {
            String category = facts.spriteOnlyCategory();
            if (category != null) {
                return ToolResult.error("这段脚本用了舞台没有的积木(" + category
                        + " 类:移动、画笔、说话、造型、碰撞、克隆只有角色能用)");
            }
        }
        List<String> defined = result.definedProcedures();
        Script replaced = null;
        if (replaces != null) {
            replaced = project.find(replaces);
            if (replaced == null) {
                return ToolResult.error("没有编号为 #" + replaces + " 的脚本;用 list_project 看现有编号");
            }
        } else if (!defined.isEmpty()) {
            // 同一个角色里同名的自定义积木只能有一个定义:再写一遍 define 就是取代旧定义
            replaced = project.definitionOf(target.name(), defined);
        }
        Script identical = replaced != null ? replaced : project.identicalTo(target.name(), body);
        if (identical != null && ScriptText.sameLines(identical.code(), body)) {
            String unchanged = "脚本 #" + identical.id() + " 没有变化:这段和已写入的完全一样,不用重交。写入成功的脚本就是完成了,继续下一段或收尾。";
            if (project.unchangedResubmit() >= STUCK_RESUBMITS) {
                return ToolResult.wrapUp(unchanged + "\n你已经把脚本都写好了,不要再重交:现在调用 final_answer 收尾。");
            }
            return ToolResult.of(unchanged, List.of());
        }
        Script script = new Script(replaced != null ? replaced.id() : project.nextId(), target.name(), body,
                result.xml(), result.blockCount(), result.newVariables(), result.newLists(), result.newLocalVariables(),
                result.newLocalLists(), result.broadcasts(), result.definedProcedures(), null, false);
        if (replaced != null) {
            project.replace(replaced, script);
        } else {
            project.add(script);
        }
        StringBuilder sb = new StringBuilder(replaced == null ? "已写入作品的脚本 #"
                : replaced.preexisting() ? "已改写作品里的脚本 #" : "已替换脚本 #").append(script.id())
                .append(" → 角色 ").append(script.sprite()).append(",").append(script.blockCount()).append(" 个积木");
        if (replaced != null && replaces == null) {
            sb.append("(自定义积木 ").append(String.join("、", defined)).append(" 的旧定义已被这版取代,不会重复)");
        }
        if (replaces != null) {
            // 并行写入时编号容易记错:把换掉的那段是什么说清楚,换错了模型自己能看出来
            String oldHat = ScriptText.firstLine(replaced.code());
            sb.append("。换掉的 #").append(replaced.id()).append(" 原来是「").append(oldHat).append("」(")
                    .append(replaced.blockCount()).append(" 个积木)");
            if (!oldHat.equals(ScriptText.firstLine(body))) {
                sb.append(",和这段的帽子不同:那段脚本的功能现在没有了");
            }
        }
        boolean rewroteWithdrawn = project.withdrawnBefore(body);
        if (rewroteWithdrawn) {
            sb.append("。注意:这段和你刚用 delete_script 删掉的一模一样,删了再写等于没改;脚本写入后就一直在,不需要重写");
        }
        if (!script.variables().isEmpty()) {
            sb.append(";新建变量:").append(String.join("、", script.variables()));
        }
        if (!script.localVariables().isEmpty()) {
            sb.append(";新建 ").append(script.sprite()).append(" 的私有变量:").append(String.join("、", script.localVariables()));
        }
        if (!script.lists().isEmpty()) {
            sb.append(";新建列表:").append(String.join("、", script.lists()));
        }
        if (!script.localLists().isEmpty()) {
            sb.append(";新建 ").append(script.sprite()).append(" 的私有列表:").append(String.join("、", script.localLists()));
        }
        for (String declared : localVariables) {
            if (!facts.variables().contains(declared)) {
                sb.append(";声明的私有变量 ").append(declared).append(" 这段脚本里没有出现,没有建");
            }
        }
        for (String declared : localLists) {
            if (!facts.lists().contains(declared)) {
                sb.append(";声明的私有列表 ").append(declared).append(" 这段脚本里没有出现,没有建");
            }
        }
        for (Diagnostic notice : result.notices()) {
            sb.append("\n提醒:").append(lines.remap(notice.message(), notice.line()));
        }
        if (replaced == null) {
            // 同一个角色里同帽子的脚本会一起运行:只说事实,是并存还是改写由模型自己定(replaces / delete_script 的用法在工具说明里)
            Script other = project.sameHatAs(script);
            if (other != null) {
                sb.append("\n注意:角色 ").append(other.sprite()).append(" 已有同帽子的脚本 #").append(other.id())
                        .append(",两段会一起运行;旧的不会因为这段而去掉。");
            }
        }
        for (String axis : List.of("x", "y")) {
            if (script.variables().contains(axis) || script.localVariables().contains(axis)) {
                sb.append("\n注意:这段新建了一个叫 ").append(axis).append(" 的变量,它和角色的 ").append(axis).append(" 坐标无关");
            }
        }
        if (facts.startsWithGreenFlag()) {
            for (String list : facts.addedLists()) {
                if (!facts.clearedLists().contains(list)) {
                    sb.append("\n提醒:这段绿旗脚本往列表 ").append(list).append(" 里 add,却没有先 delete all of [").append(list)
                            .append(" v];列表内容随作品保存,每次点绿旗都会越加越多。");
                }
            }
        }
        if (rewroteWithdrawn && project.unchangedResubmit() >= STUCK_RESUBMITS) {
            return ToolResult.wrapUp(sb + "\n删了又原样写回已经反复多次,作品不会再变:现在调用 final_answer 收尾。");
        }
        return ToolResult.of(sb.toString(), List.of());
    }

    private static String describeErrors(Result result, Lines lines) {
        StringBuilder sb = new StringBuilder();
        for (Diagnostic error : result.errors()) {
            sb.append("- 第 ").append(lines.original(error.line())).append(" 行:").append(lines.remap(error.message(), error.line()));
            if (!error.suggestions().isEmpty()) {
                sb.append("(拼写相近的积木:").append(String.join("、", error.suggestions())).append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static ToolResult createSprite(Project project, String name) {
        String spriteName = name.strip();
        if (spriteName.isEmpty()) {
            return ToolResult.error("缺少角色名");
        }
        if (project.sprite(spriteName) != null) {
            return ToolResult.of("角色 " + spriteName + " 已经存在,直接用", List.of());
        }
        Sprite template = project.sprites().stream().filter(s -> !s.isStage()).findFirst().orElse(null);
        project.createSprite(spriteName);
        return ToolResult.of("已新建角色 " + spriteName + (template == null ? "" : "(造型和声音同 " + template.name() + ")"), List.of());
    }

    private static ToolResult deleteSprite(Project project, String name) {
        String spriteName = name.strip();
        Sprite sprite = project.sprite(spriteName);
        if (sprite == null) {
            return ToolResult.error("作品里没有叫 \"" + spriteName + "\" 的角色;现有:" + spriteNames(project));
        }
        if (sprite.isStage()) {
            return ToolResult.error("舞台不能删");
        }
        boolean preexisting = project.preexistingSprite(spriteName);
        List<Script> removed = project.deleteSprite(spriteName);
        StringBuilder sb = new StringBuilder("已删掉角色 ").append(spriteName).append(",连同它的 ").append(removed.size()).append(" 段脚本");
        if (preexisting) {
            sb.append(";这是作品里原有的角色,它的造型、声音回退时恢复不了");
        }
        return ToolResult.of(sb.toString(), List.of());
    }

    private static ToolResult deleteVariable(Project project, String name, String sprite, boolean list) {
        String kind = list ? "列表" : "变量";
        String target = name.strip();
        if (target.isEmpty()) {
            return ToolResult.error("缺少 name");
        }
        List<String> holders = project.holdersOf(target, list);
        if (holders.isEmpty()) {
            return ToolResult.error("没有叫 \"" + target + "\" 的" + kind);
        }
        String owner;
        if (sprite.isBlank()) {
            if (holders.size() > 1) {
                return ToolResult.error(kind + " \"" + target + "\" 有 " + holders.size() + " 份:" + String.join("、", holders.stream().map(ScratchTools::describeHolder).toList())
                        + ";用 sprite 指明删哪一份(Stage 指全局的那份)");
            }
            owner = holders.getFirst();
        } else {
            owner = "Stage".equals(sprite.strip()) ? null : sprite.strip();
            if (!holders.contains(owner)) {
                return ToolResult.error(describeHolder(owner) + "没有" + kind + " \"" + target + "\";它在:" + String.join("、", holders.stream().map(ScratchTools::describeHolder).toList()));
            }
        }
        List<Script> users = project.usersOf(target, list, owner);
        if (!users.isEmpty()) {
            return ToolResult.error(describeHolder(owner) + "的" + kind + " \"" + target + "\" 还在被脚本用着:" + String.join("、", users.stream()
                    .map(s -> "#" + s.id() + "(角色 " + s.sprite() + ")").toList()));
        }
        project.deleteVariable(target, list, owner);
        return ToolResult.of("已删掉" + describeHolder(owner) + "的" + kind + " " + target, List.of());
    }

    private static String describeHolder(String owner) {
        return owner == null ? "全局" : "角色 " + owner + " 私有";
    }

    private ToolResult finalAnswer(Project project, String text) {
        String content = text == null ? "" : text.strip();
        if (content.isEmpty()) {
            return ToolResult.error("text 是空的:把要说的话写进 text");
        }
        if (INLINE_TOOL_CALL.matcher(content).find()) {
            return ToolResult.error("回复里把工具调用写成了文字,它不会被执行。请真正调用工具,final_answer 的 text 只留给用户的说明。");
        }
        List<String> problems = fenceProblems(content, fenceKnown(project, content));
        if (!problems.isEmpty()) {
            return ToolResult.error("回复里的积木图有错,改好再 final_answer(用户看到的积木必须是真实存在、写法正确的):\n" + String.join("\n", problems));
        }
        if (!project.diff().isEmpty() && project.wrappingUp()) {
            // 步数已用完:打回也没有轮次去修,只会逼模型在最后几步乱删。照单全收,检查出的问题如实附在回复里
            List<String> finish = ProgramChecks.finishProblems(project.all());
            if (!finish.isEmpty()) {
                content = content + "\n\n" + WRAP_UP_CHECK_HEADING + "\n- " + String.join("\n- ", finish);
            }
        } else if (!project.diff().isEmpty() && project.firstFinishCheck()) {
            List<String> finish = ProgramChecks.finishProblems(project.all());
            if (!finish.isEmpty()) {
                return ToolResult.error("先修好这些再 final_answer(每条都是能跑但一定不对的程序):\n- " + String.join("\n- ", finish));
            }
        }
        project.finish(content);
        return ToolResult.terminal(content);
    }

    /**
     * 画给用户看的积木图里能调用的自定义积木:这条回复自己画的 define、之前回复里画过的 define(讲解模式由此认得),
     * 以及作品任何角色里定义过的(讲解不分角色);名字不对照作品
     */
    private Known fenceKnown(Project project, String content) {
        Map<String, Procedure> procedures = new LinkedHashMap<>();
        proceduresIn(List.of(content)).forEach(p -> procedures.putIfAbsent(p.proccode(), p));
        project.inheritedProcedures().forEach(p -> procedures.putIfAbsent(p.proccode(), p));
        for (Sprite sprite : project.sprites()) {
            project.proceduresOf(sprite.name()).forEach(p -> procedures.putIfAbsent(p.proccode(), p));
        }
        return new Known(Set.of(), Set.of(), List.copyOf(procedures.values()), null);
    }

    public List<Procedure> proceduresIn(List<String> replies) {
        Map<String, Procedure> procedures = new LinkedHashMap<>();
        for (String reply : replies) {
            Matcher fence = BLOCK_FENCE.matcher(reply == null ? "" : reply);
            while (fence.find()) {
                Lines lines = Lines.withoutBlank(fence.group(1).strip());
                if (!lines.text().startsWith("define ")) {
                    continue;
                }
                Result result = engine.check(lines.text(), Known.empty());
                if (result.ok()) {
                    ProcedureXml.definitions(result.xml()).forEach(p -> procedures.putIfAbsent(p.proccode(), p));
                }
            }
        }
        return List.copyOf(procedures.values());
    }

    /**
     * 正文里每个积木围栏单独查(只查不写):按围栏序号列出错误。只查积木名、形状与括号,不对照作品里的名字
     * (讲解模式看不到作品;举例时也常用假想的造型 / 声音名)。围栏里的空行不当分段,和前端渲染一致
     */
    List<String> fenceProblems(String content, Known known) {
        List<String> problems = new ArrayList<>();
        Matcher fence = BLOCK_FENCE.matcher(content);
        int index = 0;
        while (fence.find()) {
            index++;
            Lines lines = Lines.withoutBlank(fence.group(1).strip());
            if (lines.text().isEmpty()) {
                continue;
            }
            Result result = engine.check(lines.text(), known);
            if (!result.ok()) {
                problems.add("第 " + index + " 个积木图:\n" + describeErrors(result, lines).stripTrailing());
            }
        }
        return problems;
    }

    private static ToolResult askUser(Project project, JsonNode args) {
        String text = args.path("question").asText("").strip();
        if (text.isEmpty()) {
            return ToolResult.error("question 是空的");
        }
        List<String> options = new ArrayList<>();
        args.path("options").forEach(option -> {
            String value = option.asText("").strip();
            if (!value.isEmpty()) {
                options.add(value);
            }
        });
        project.ask(new Question(text, List.copyOf(options)));
        return ToolResult.terminal(text);
    }

    private static ToolResult deleteScript(Project project, int id) {
        Script target = project.find(id);
        if (target == null) {
            return ToolResult.error("没有编号为 #" + id + " 的脚本;用 list_project 看现有编号");
        }
        String firstLine = target.code().lines().findFirst().orElse("");
        ScriptChange recorded = project.delete(target);
        String note = recorded != null && !target.preexisting()
                ? "。注意:#" + id + " 是你本轮刚改写过的,现在连它原来的版本一起从作品里删掉了" : "";
        return ToolResult.of((recorded == null ? "已撤掉本轮写的脚本 #" : "已删掉脚本 #") + id + "(角色 " + target.sprite() + ":" + firstLine + ")" + note, List.of());
    }

    private static String spriteNames(Project project) {
        List<Sprite> all = project.sprites();
        return all.isEmpty() ? "(无)" : String.join("、", all.stream().map(Sprite::name).toList());
    }
}
