package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.blockcoding.application.ChatViews.MessageChanges;
import cn.utcy.teaching.blockcoding.application.ChatViews.ScriptView;
import cn.utcy.teaching.blockcoding.application.ChatViews.SpriteChangeView;
import cn.utcy.teaching.blockcoding.application.ChatViews.VariableChangeView;
import cn.utcy.teaching.blockcoding.domain.BlockCodingChatMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型看到的历史:双方文字。助手消息附一句本轮改了什么(哪个角色的哪段、建 / 删了什么,及是否被回退),用户消息附上当时拖进来的积木;
 * 最近 20 条、3 万字符封顶。修改模式里作品的真相不在历史里,每轮由快照带来;讲解模式上一轮画的积木只在历史里。
 */
final class ChatHistory {
    static final int MAX_MESSAGES = 20;
    static final int CHAR_BUDGET = 30_000;

    private ChatHistory() {
    }

    static String summarize(MessageChanges changes) {
        List<String> parts = new ArrayList<>();
        for (SpriteChangeView sprite : changes.sprites()) {
            parts.add(("created".equals(sprite.kind()) ? "新建角色 " : "删除角色 ") + sprite.name());
        }
        for (ScriptView script : changes.scripts()) {
            String verb = "replaced".equals(script.kind()) ? "改写" : "deleted".equals(script.kind()) ? "删除" : "新写";
            String firstLine = script.code() == null ? "" : script.code().lines().findFirst().orElse("").strip();
            parts.add(verb + " " + script.sprite() + " 的 " + firstLine);
        }
        for (VariableChangeView variable : changes.variables()) {
            if ("deleted".equals(variable.kind())) {
                parts.add("删" + (variable.list() ? "列表 " : "变量 ") + variable.name());
            }
        }
        return String.join(";", parts);
    }

    static List<ChatMessage> of(MessageRecords records, List<BlockCodingChatMessage> prior) {
        List<ChatMessage> out = new ArrayList<>();
        long chars = 0;
        for (int i = prior.size() - 1; i >= 0 && out.size() < MAX_MESSAGES; i--) {
            BlockCodingChatMessage message = prior.get(i);
            String text = message.getContent() == null ? "" : message.getContent();
            MessageChanges scripts = records.read(message);
            if ("assistant".equals(message.getRole())) {
                if (scripts.touchesProject()) {
                    text = text + "\n(这轮:" + summarize(scripts) + (scripts.reverted() ? ";用户随后把这些改动全部回退了" : "") + ")";
                }
            } else {
                for (ScriptView script : scripts.scripts()) {
                    text = text + "\n(正文里的【" + script.label() + "】,角色 " + script.sprite() + ")\n```\n" + script.code() + "\n```";
                }
            }
            if (text.isBlank()) {
                continue;
            }
            chars += text.length();
            if (chars > CHAR_BUDGET) {
                break;
            }
            out.addFirst("assistant".equals(message.getRole()) ? AiMessage.from(text) : UserMessage.from(text));
        }
        return out;
    }
}
