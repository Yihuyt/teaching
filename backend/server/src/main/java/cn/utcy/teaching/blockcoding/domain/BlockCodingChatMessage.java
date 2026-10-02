package cn.utcy.teaching.blockcoding.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("blockcoding_chat_message")
public class BlockCodingChatMessage {
    @TableId
    private Long id;
    private Long sessionId;
    private Integer seq;
    private String role;
    private String content;
    private String scriptsJson;
    private Instant createdAt;

    protected BlockCodingChatMessage() {
    }

    public static BlockCodingChatMessage of(long sessionId, int seq, String role, String content, String scriptsJson) {
        BlockCodingChatMessage message = new BlockCodingChatMessage();
        message.sessionId = sessionId;
        message.seq = seq;
        message.role = role;
        message.content = content;
        message.scriptsJson = scriptsJson;
        message.createdAt = Instant.now();
        return message;
    }

    public Long getId() {
        return id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public Integer getSeq() {
        return seq;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getScriptsJson() {
        return scriptsJson;
    }

    public void setScriptsJson(String scriptsJson) {
        this.scriptsJson = scriptsJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
