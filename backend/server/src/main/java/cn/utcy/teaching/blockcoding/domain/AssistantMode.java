package cn.utcy.teaching.blockcoding.domain;

import java.util.List;

public enum AssistantMode {
    /** 讲解:只用文字和积木图讲解,不碰作品 */
    CHAT,
    /** 修改:读技能、写脚本、删脚本、建角色,直接落进编辑器里的作品 */
    AGENT;

    /** 能用的模式,第一个是界面上的默认:课程管理者修改 / 讲解,其他人只有讲解 */
    public static List<AssistantMode> allowedFor(boolean courseManager) {
        return courseManager ? List.of(AGENT, CHAT) : List.of(CHAT);
    }

    public static AssistantMode resolve(List<AssistantMode> allowed, String requested) {
        if (requested != null && AGENT.key().equals(requested.strip().toLowerCase()) && allowed.contains(AGENT)) {
            return AGENT;
        }
        return CHAT;
    }

    public String key() {
        return name().toLowerCase();
    }
}
