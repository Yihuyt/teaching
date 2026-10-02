package cn.utcy.teaching.ai.application;

import cn.utcy.teaching.ai.infrastructure.AiConfigCrypto;
import cn.utcy.teaching.ai.infrastructure.UserAiConfigEntity;
import cn.utcy.teaching.ai.infrastructure.UserAiConfigMapper;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 用户级 AI 服务配置:大模型 API Key 与 MinerU 令牌都是"人"的属性,
 * 配一次全平台生效。课程内的一切 AI 消费(含学生触发的问答/助教)统一
 * 解析**课程负责人**的配置;无课程上下文(个人积木项目)用本人的。
 * 平台不持有业务密钥——未配置即明确报错,没有兜底。
 * 密钥 AES-GCM 加密落库,**永不回显**:管理面只给"已配置 + 尾号",
 * 只能覆盖或清除;明文只在服务端调用上游时短暂解出。
 */
@Service
public class UserAiConfigService {

    private static final int TAIL_CHARS = 4;

    private final UserAiConfigMapper configs;
    private final AiConfigCrypto crypto;
    private final CurrentActor currentActor;

    public UserAiConfigService(UserAiConfigMapper configs, AiConfigCrypto crypto,
                               CurrentActor currentActor) {
        this.configs = configs;
        this.crypto = crypto;
        this.currentActor = currentActor;
    }

    /** 尾号为空串表示未配置 */
    public record ConfigView(boolean llmConfigured, String llmKeyTail,
                             boolean mineruConfigured, String mineruTokenTail) {
    }

    @Transactional(readOnly = true)
    public ConfigView viewForCurrentUser() {
        return toView(configs.selectById(currentActor.require().userId()));
    }

    @Transactional
    public ConfigView updateLlmKey(String apiKey) {
        return updateSecret(apiKey, "大模型 API Key",
                UserAiConfigEntity::setLlmApiKeyCipher,
                (userId, cipher, now) -> new UserAiConfigEntity(userId, cipher, null, now, now));
    }

    @Transactional
    public ConfigView clearLlmKey() {
        return clearSecret(UserAiConfigEntity::getLlmApiKeyCipher,
                UserAiConfigEntity::setLlmApiKeyCipher);
    }

    @Transactional
    public ConfigView updateMineruToken(String token) {
        return updateSecret(token, "MinerU 令牌",
                UserAiConfigEntity::setMineruTokenCipher,
                (userId, cipher, now) -> new UserAiConfigEntity(userId, null, cipher, now, now));
    }

    @Transactional
    public ConfigView clearMineruToken() {
        return clearSecret(UserAiConfigEntity::getMineruTokenCipher,
                UserAiConfigEntity::setMineruTokenCipher);
    }

    /**
     * 消费链路取大模型 Key:missingHint 由调用方给出(它知道该提示谁去配),
     * 鉴权也由调用方完成。
     */
    @Transactional(readOnly = true)
    public String requireLlmKey(long userId, String missingHint) {
        UserAiConfigEntity config = configs.selectById(userId);
        if (config == null || config.getLlmApiKeyCipher() == null) {
            throw new BadRequestException(missingHint);
        }
        return crypto.decrypt(config.getLlmApiKeyCipher());
    }

    @Transactional(readOnly = true)
    public String requireMineruToken(long userId, String missingHint) {
        UserAiConfigEntity config = configs.selectById(userId);
        if (config == null || config.getMineruTokenCipher() == null) {
            throw new BadRequestException(missingHint);
        }
        return crypto.decrypt(config.getMineruTokenCipher());
    }

    private interface EntityCreator {
        UserAiConfigEntity create(long userId, byte[] cipher, LocalDateTime now);
    }

    private ConfigView updateSecret(String secret, String label,
                                    BiConsumer<UserAiConfigEntity, byte[]> setter,
                                    EntityCreator creator) {
        String trimmed = secret == null ? "" : secret.trim();
        if (trimmed.isEmpty()) {
            throw new BadRequestException(label + "不能为空(清除配置请用删除操作)");
        }
        long userId = currentActor.require().userId();
        byte[] cipher = crypto.encrypt(trimmed);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        UserAiConfigEntity config = configs.selectById(userId);
        if (config == null) {
            config = creator.create(userId, cipher, now);
            configs.insert(config);
        } else {
            setter.accept(config, cipher);
            config.setUpdatedAt(now);
            configs.updateById(config);
        }
        return toView(config);
    }

    private ConfigView clearSecret(Function<UserAiConfigEntity, byte[]> getter,
                                   BiConsumer<UserAiConfigEntity, byte[]> setter) {
        UserAiConfigEntity config = configs.selectById(currentActor.require().userId());
        if (config != null && getter.apply(config) != null) {
            setter.accept(config, null);
            config.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            configs.updateById(config);
        }
        return toView(config);
    }

    private ConfigView toView(UserAiConfigEntity config) {
        return new ConfigView(
                config != null && config.getLlmApiKeyCipher() != null,
                tail(config == null ? null : config.getLlmApiKeyCipher()),
                config != null && config.getMineruTokenCipher() != null,
                tail(config == null ? null : config.getMineruTokenCipher()));
    }

    private String tail(byte[] cipher) {
        if (cipher == null) {
            return "";
        }
        String secret = crypto.decrypt(cipher);
        return secret.length() <= TAIL_CHARS
                ? secret : secret.substring(secret.length() - TAIL_CHARS);
    }
}
