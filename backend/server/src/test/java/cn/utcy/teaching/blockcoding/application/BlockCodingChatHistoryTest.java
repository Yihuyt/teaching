package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.ai.agent.ChatAgentLoop;
import cn.utcy.teaching.blockcoding.domain.BlockCodingChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BlockCodingChatHistoryTest {
    @Test
    @DisplayName("助手消息附一句改了什么(角色、脚本首行、变量),用户消息原样")
    void summarizesScripts() {
        BlockCodingChatMessage applied = BlockCodingChatMessage.of(1L, 4, "assistant", "改好了",
                "{\"scripts\":[{\"sprite\":\"Apple\",\"code\":\"when green flag clicked\\nhide\",\"kind\":\"replaced\"}],\"question\":null}");
        List<BlockCodingChatMessage> prior = List.of(
                BlockCodingChatMessage.of(1L, 1, "user", "把【积木1】改成接苹果",
                        "{\"scripts\":[{\"id\":1,\"sprite\":\"Cat\",\"code\":\"when green flag clicked\\nmove (10) steps\",\"blockCount\":2,\"kind\":\"quoted\",\"label\":\"积木1\"}],\"question\":null}"),
                BlockCodingChatMessage.of(1L, 2, "assistant", "做好了",
                        "{\"scripts\":[{\"sprite\":\"Apple\",\"code\":\"when green flag clicked\\nhide\",\"kind\":\"written\"},{\"sprite\":\"Bowl\",\"code\":\"when I receive [go v]\\nshow\",\"kind\":\"written\"}],\"sprites\":[{\"name\":\"Bowl\",\"kind\":\"created\",\"preexisting\":false}],\"variables\":[{\"name\":\"j\",\"list\":false,\"sprite\":null,\"kind\":\"deleted\"}],\"question\":null}"),
                BlockCodingChatMessage.of(1L, 3, "user", "苹果快一点", null),
                applied,
                BlockCodingChatMessage.of(1L, 5, "user", "算了", null),
                BlockCodingChatMessage.of(1L, 6, "assistant", "已删掉",
                        "{\"scripts\":[{\"sprite\":\"Apple\",\"code\":\"when green flag clicked\\nhide\",\"kind\":\"deleted\"}],\"question\":null,\"reverted\":true}"));
        List<ChatMessage> history = ChatHistory.of(new MessageRecords(new ObjectMapper()), prior);
        assertThat(history).hasSize(6);
        assertThat(ChatAgentLoop.textOf(history.get(5))).isEqualTo("已删掉\n(这轮:删除 Apple 的 when green flag clicked;用户随后把这些改动全部回退了)");
        assertThat(history.get(0)).isInstanceOf(UserMessage.class);
        assertThat(ChatAgentLoop.textOf(history.get(0))).isEqualTo("把【积木1】改成接苹果\n(正文里的【积木1】,角色 Cat)\n```\nwhen green flag clicked\nmove (10) steps\n```");
        assertThat(ChatAgentLoop.textOf(history.get(1))).isEqualTo("做好了\n(这轮:新建角色 Bowl;新写 Apple 的 when green flag clicked;新写 Bowl 的 when I receive [go v];删变量 j)");
        assertThat(ChatAgentLoop.textOf(history.get(3))).isEqualTo("改好了\n(这轮:改写 Apple 的 when green flag clicked)");
    }

    @Test
    @DisplayName("只保留最近 20 条,顺序不变")
    void capsAtTwentyMessages() {
        List<BlockCodingChatMessage> prior = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            prior.add(BlockCodingChatMessage.of(1L, i, i % 2 == 1 ? "user" : "assistant", "m" + i, null));
        }
        List<ChatMessage> history = ChatHistory.of(new MessageRecords(new ObjectMapper()), prior);
        assertThat(history).hasSize(20);
        assertThat(ChatAgentLoop.textOf(history.getFirst())).isEqualTo("m11");
        assertThat(ChatAgentLoop.textOf(history.getLast())).isEqualTo("m30");
    }
}
