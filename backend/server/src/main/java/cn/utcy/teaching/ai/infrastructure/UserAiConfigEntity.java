package cn.utcy.teaching.ai.infrastructure;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 用户级 AI 服务配置(密钥列存 AES-GCM 密文):大模型 API Key + MinerU 令牌。
 * 密钥跟人走——课程内消费统一解析课程负责人的配置。
 * 可空密文列清除时必须显式写 NULL(updateById 默认跳过 null 字段,必须 ALWAYS)。
 */
@TableName("user_ai_config")
public class UserAiConfigEntity {

    @TableId
    private Long userId;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private byte[] llmApiKeyCipher;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private byte[] mineruTokenCipher;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    protected UserAiConfigEntity() {
    }

    public UserAiConfigEntity(long userId, byte[] llmApiKeyCipher, byte[] mineruTokenCipher,
                              LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.userId = userId;
        this.llmApiKeyCipher = llmApiKeyCipher;
        this.mineruTokenCipher = mineruTokenCipher;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getUserId() {
        return userId;
    }

    public byte[] getLlmApiKeyCipher() {
        return llmApiKeyCipher;
    }

    public void setLlmApiKeyCipher(byte[] llmApiKeyCipher) {
        this.llmApiKeyCipher = llmApiKeyCipher;
    }

    public byte[] getMineruTokenCipher() {
        return mineruTokenCipher;
    }

    public void setMineruTokenCipher(byte[] mineruTokenCipher) {
        this.mineruTokenCipher = mineruTokenCipher;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
