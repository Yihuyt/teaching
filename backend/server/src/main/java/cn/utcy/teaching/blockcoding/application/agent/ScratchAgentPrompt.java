package cn.utcy.teaching.blockcoding.application.agent;

import cn.utcy.teaching.blockcoding.application.agent.SkillCatalog;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 积木助手的提示词装配:每种模式各一份系统提示(文件名由 {@link Workspace#systemTemplate()} 给),里面只有机器规则
 * (工具怎么用、记法、检查、技能花名册);"怎么讲"是教学方法,只由课程教师配置(辅导提示词),拼进 final_answer 工具的说明;没配就什么都不加。
 * 积木写法表不进系统提示——由技能按需给;用户消息 = 作品(有的话,一个角色一块)+ 拖进来的积木 + 用户的话(放最后)。
 */
public final class ScratchAgentPrompt {
    private final Map<String, String> templates = new ConcurrentHashMap<>();
    private final SkillCatalog skills;

    public ScratchAgentPrompt(SkillCatalog skills) {
        this.skills = skills;
    }

    /** 生效的辅导提示词:教师配了就是教师的原文,没配就是空——平台不预设任何教学方法 */
    public static String guidance(String teacherGuidance) {
        return teacherGuidance == null ? "" : teacherGuidance.strip();
    }

    public String system(Workspace workspace) {
        String template = templates.computeIfAbsent(workspace.systemTemplate(), ScratchAgentPrompt::load);
        return template.replace("{{roster}}", skills.roster()).strip();
    }

    public record Quoted(String label, Script script) {
    }

    /**
     * 用户消息:先是工作台(一个角色一块:造型、声音、私有名字、自定义积木、它的脚本;最后是全局),再是拖进来的积木,
     * 用户的话放在最后——离生成点最近。
     */
    public String user(Workspace workspace, Project project, String request, List<Quoted> quoted) {
        StringBuilder sb = new StringBuilder();
        if (!project.isEmpty()) {
            project.describe(sb, "作品");
        }
        if (quoted != null && !quoted.isEmpty()) {
            sb.append("\n## 用户指着说的积木(正文里的【积木N】标记就是下面这些)\n");
            for (Quoted item : quoted) {
                Script script = item.script();
                Script same = project.all().stream()
                        .filter(e -> e.blockId() != null && e.blockId().equals(script.blockId())).findFirst().orElse(null);
                sb.append("### ").append(item.label()).append("(角色 ").append(script.sprite());
                if (same != null) {
                    sb.append(";就是上面的脚本 #").append(same.id());
                }
                sb.append(")\n```\n").append(script.code().strip()).append("\n```\n");
            }
        }
        sb.append("\n## 用户这次说的\n").append(request.strip()).append('\n');
        return sb.toString();
    }

    private static String load(String name) {
        try (InputStream stream = ScratchAgentPrompt.class.getResourceAsStream("/blockcoding/agent/" + name)) {
            if (stream == null) {
                throw new IllegalStateException("缺少提示词资源: blockcoding/agent/" + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("读取提示词资源失败: " + name, exception);
        }
    }
}
