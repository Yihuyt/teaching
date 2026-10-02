package cn.utcy.teaching.blockcoding.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("blockcoding_chat_session")
public class BlockCodingChatSession {
    @TableId
    private Long id;
    private Long projectId;
    private Long accountId;
    private Instant createdAt;
    private Instant updatedAt;

    protected BlockCodingChatSession() {
    }

    public static BlockCodingChatSession create(long projectId, long accountId) {
        BlockCodingChatSession session = new BlockCodingChatSession();
        Instant now = Instant.now();
        session.projectId = projectId;
        session.accountId = accountId;
        session.createdAt = now;
        session.updatedAt = now;
        return session;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
