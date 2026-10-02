package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.blockcoding.application.ChatViews.MessageChanges;
import cn.utcy.teaching.blockcoding.application.ChatViews.MessageView;
import cn.utcy.teaching.blockcoding.domain.BlockCodingChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class MessageRecords {
    private final ObjectMapper objectMapper;

    public MessageRecords(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String write(MessageChanges scripts) {
        if (scripts == null || (!scripts.touchesProject() && scripts.question() == null && !scripts.reverted())) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(scripts);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("消息的脚本记录序列化失败", exception);
        }
    }

    public MessageChanges read(BlockCodingChatMessage message) {
        if (message.getScriptsJson() == null) {
            return MessageChanges.NONE;
        }
        try {
            return objectMapper.readValue(message.getScriptsJson(), MessageChanges.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("消息 " + message.getId() + " 的脚本记录损坏", exception);
        }
    }

    public MessageView view(BlockCodingChatMessage message) {
        MessageChanges changes = read(message);
        return new MessageView(message.getId(), message.getSeq(), message.getRole(), message.getContent(),
                changes.scripts(), changes.sprites(), changes.variables(), changes.question(), changes.reverted(), message.getCreatedAt());
    }
}
