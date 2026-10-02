package cn.utcy.teaching.blockcoding.application.agent;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 两种模式只差一件事:有没有作品。
 * PROJECT(修改模式):用户的作品抄进来变成工作台,模型用工具改它,收尾时写进编辑器。
 * CHAT(讲解模式):没有作品,也就没有工作台和改工作台的工具;模型只读技能、写回复,回复里的积木图逐段编译检查。
 * 循环、提示结构、编译器、积木图检查全部共用。
 */
public enum Workspace {
    PROJECT(List.of(ScratchTools.READ_SKILL, ScratchTools.WRITE_SCRIPT, ScratchTools.LIST_PROJECT, ScratchTools.CREATE_SPRITE,
            ScratchTools.DELETE_SCRIPT, ScratchTools.DELETE_SPRITE, ScratchTools.DELETE_VARIABLE, ScratchTools.DELETE_LIST,
            ScratchTools.FINAL_ANSWER, ScratchTools.ASK_USER),
            "agent-system.md",
            "本轮到此为止,不能再写了。现在调用 final_answer 收尾:说明做了什么、怎么玩,没做完的如实说明;要问用户就调用 ask_user。"),

    CHAT(List.of(ScratchTools.READ_SKILL, ScratchTools.FINAL_ANSWER),
            "chat-system.md",
            "本轮到此为止。现在调用 final_answer 把要告诉用户的话说完,没想清楚的如实说明。");

    private final Set<String> tools;
    private final String systemTemplate;
    private final String wrapUpReminder;

    Workspace(List<String> tools, String systemTemplate, String wrapUpReminder) {
        this.tools = new LinkedHashSet<>(tools);
        this.systemTemplate = systemTemplate;
        this.wrapUpReminder = wrapUpReminder;
    }

    public Set<String> tools() {
        return tools;
    }

    public String systemTemplate() {
        return systemTemplate;
    }

    public String wrapUpReminder() {
        return wrapUpReminder;
    }
}
