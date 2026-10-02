package cn.utcy.teaching.blockcoding.application.agent;

import cn.utcy.teaching.blockcoding.application.agent.SkillCatalog;
import cn.utcy.teaching.blockcoding.application.HarvestPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ScratchAgentPromptTest {
    private final ScratchAgentPrompt prompt = new ScratchAgentPrompt(new SkillCatalog("blockcoding/skills"));

    private static HarvestPayload harvest() {
        List<HarvestPayload.SpriteContext> sprites = List.of(
                new HarvestPayload.SpriteContext("Cat", false, List.of("造型1"), List.of("Meow"), List.of(), List.of()),
                new HarvestPayload.SpriteContext("Stage", true, List.of("背景1"), List.of(), List.of(), List.of()));
        return new HarvestPayload(sprites, List.of("分数"), List.of(), List.of(), Map.of(), "Cat", Map.of());
    }

    @Test
    @DisplayName("辅导方式:教师配了就是原文,没配就是空(平台不预设教学方法);系统提示里没有这一节")
    void guidanceIsTeachersOrNothing() {
        assertThat(ScratchAgentPrompt.guidance(null)).isEmpty();
        assertThat(ScratchAgentPrompt.guidance("  ")).isEmpty();
        assertThat(ScratchAgentPrompt.guidance(" 只用提问引导,绝不给出任何积木。 ")).isEqualTo("只用提问引导,绝不给出任何积木。");
        for (Workspace workspace : List.of(Workspace.CHAT, Workspace.PROJECT)) {
            String system = prompt.system(workspace);
            assertThat(system).doesNotContain("辅导方式").doesNotContain("{{guidance}}").doesNotContain("{{roster}}").contains("可读的技能");
        }
        assertThat(prompt.system(Workspace.CHAT)).contains("不改学生的作品");
        assertThat(prompt.system(Workspace.PROJECT)).contains("一次性写进作品");
    }

    @Test
    @DisplayName("讲解模式:没有作品那一节,只有拖进来的积木和用户的话(用户的话在最后)")
    void chatModeListsQuotedBlocksThenTheRequest() {
        Script quotedScript = Script.preexisting(1, "Cat", "when green flag clicked\nmove (10) steps", "<xml/>", 2, List.of(), "top-a");
        String user = prompt.user(Workspace.CHAT, Project.empty(), "这段【积木1】为什么不动?", List.of(new ScratchAgentPrompt.Quoted("积木1", quotedScript)));
        assertThat(user).isEqualTo("\n## 用户指着说的积木(正文里的【积木N】标记就是下面这些)\n### 积木1(角色 Cat)\n```\nwhen green flag clicked\nmove (10) steps\n```\n"
                + "\n## 用户这次说的\n这段【积木1】为什么不动?\n");
        assertThat(prompt.user(Workspace.CHAT, Project.empty(), "怎么让小猫走?", List.of()))
                .isEqualTo("\n## 用户这次说的\n怎么让小猫走?\n");
    }

    @Test
    @DisplayName("作品:一个角色一块(造型、声音、它的脚本),全局一段,拖进来的积木指认作品里的脚本编号,用户的话在最后")
    void projectGroupsBySpriteAndIdentifiesQuotedScripts() {
        Script existing = Script.preexisting(2, "Cat", "when green flag clicked\nmove (10) steps", "<xml/>", 2, List.of(), "top-a");
        Project project = Project.fromHarvest(harvest(), List.of(existing));
        Script quotedScript = Script.preexisting(1, "Cat", "when green flag clicked\nmove (10) steps", "<xml/>", 2, List.of(), "top-a");
        Script other = Script.preexisting(2, "Dog", "when [space v] key pressed\nsay [hi]", "<xml/>", 2, List.of(), "top-z");
        String user = prompt.user(Workspace.PROJECT, project, "这段【积木1】为什么不动,【积木2】却可以?",
                List.of(new ScratchAgentPrompt.Quoted("积木1", quotedScript), new ScratchAgentPrompt.Quoted("积木2", other)));
        assertThat(user).startsWith("## 作品(编号只在这一轮有效)\n### 角色 Cat\n- 造型:造型1;声音:Meow;私有变量:无;私有列表:无\n#### 脚本 #2\n")
                .contains("### 舞台 Stage\n- 造型:背景1;声音:无\n- 脚本:无\n## 全局\n- 变量:分数;列表:无;广播:无\n- 用户此刻在编辑器里选中的角色:Cat\n")
                .contains("### 积木1(角色 Cat;就是上面的脚本 #2)\n```\nwhen green flag clicked\nmove (10) steps\n```")
                .contains("### 积木2(角色 Dog)\n```\nwhen [space v] key pressed\nsay [hi]\n```")
                .endsWith("## 用户这次说的\n这段【积木1】为什么不动,【积木2】却可以?\n");
    }
}
