package cn.utcy.teaching.ai.application;

import cn.utcy.teaching.ai.infrastructure.AiConfigCrypto;
import cn.utcy.teaching.ai.infrastructure.AiConfigProperties;
import cn.utcy.teaching.ai.infrastructure.UserAiConfigEntity;
import cn.utcy.teaching.ai.infrastructure.UserAiConfigMapper;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.actor.SystemRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAiConfigServiceTest {

    private static final Actor TEACHER = new Actor(7L, "teacher", SystemRole.TEACHER);

    @TempDir
    static Path dir;

    private final UserAiConfigMapper configs = mock(UserAiConfigMapper.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private UserAiConfigService service;
    private AiConfigCrypto crypto;

    @BeforeEach
    void setUp() throws Exception {
        Path keyFile = dir.resolve("key");
        if (!Files.exists(keyFile)) {
            byte[] raw = new byte[32];
            Files.writeString(keyFile, Base64.getEncoder().encodeToString(raw),
                    StandardCharsets.UTF_8);
        }
        crypto = new AiConfigCrypto(new AiConfigProperties(keyFile));
        service = new UserAiConfigService(configs, crypto, currentActor);
        when(currentActor.require()).thenReturn(TEACHER);
    }

    @Test
    void 配置大模型Key_加密落库_视图只回尾号() {
        service.updateLlmKey("  sk-dashscope-abcd1234  ");

        ArgumentCaptor<UserAiConfigEntity> captor =
                ArgumentCaptor.forClass(UserAiConfigEntity.class);
        verify(configs).insert(captor.capture());
        UserAiConfigEntity saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(new String(saved.getLlmApiKeyCipher(), StandardCharsets.ISO_8859_1))
                .doesNotContain("sk-dashscope");
        assertThat(crypto.decrypt(saved.getLlmApiKeyCipher())).isEqualTo("sk-dashscope-abcd1234");

        when(configs.selectById(7L)).thenReturn(saved);
        UserAiConfigService.ConfigView view = service.viewForCurrentUser();
        assertThat(view.llmConfigured()).isTrue();
        assertThat(view.llmKeyTail()).isEqualTo("1234");
        assertThat(view.mineruConfigured()).isFalse();
    }

    @Test
    void 两项配置互不影响_更新MinerU不动大模型Key() {
        UserAiConfigEntity existing = new UserAiConfigEntity(
                7L, crypto.encrypt("sk-x"), null, LocalDateTime.now(), LocalDateTime.now());
        when(configs.selectById(7L)).thenReturn(existing);

        UserAiConfigService.ConfigView view = service.updateMineruToken("jwt-token-zz99");

        assertThat(view.llmConfigured()).isTrue();
        assertThat(view.mineruConfigured()).isTrue();
        assertThat(view.mineruTokenTail()).isEqualTo("zz99");
        assertThat(crypto.decrypt(existing.getLlmApiKeyCipher())).isEqualTo("sk-x");
    }

    @Test
    void 清除配置_把密钥列显式写回NULL() {
        UserAiConfigEntity existing = new UserAiConfigEntity(
                7L, crypto.encrypt("sk-x"), null, LocalDateTime.now(), LocalDateTime.now());
        when(configs.selectById(7L)).thenReturn(existing);

        UserAiConfigService.ConfigView view = service.clearLlmKey();

        assertThat(view.llmConfigured()).isFalse();
        ArgumentCaptor<UserAiConfigEntity> captor =
                ArgumentCaptor.forClass(UserAiConfigEntity.class);
        verify(configs).updateById(captor.capture());
        assertThat(captor.getValue().getLlmApiKeyCipher()).isNull();
    }

    @Test
    void 未配置时消费链路取密钥_按调用方口径报错() {
        assertThatThrownBy(() -> service.requireLlmKey(9L, "课程负责人尚未配置大模型 API Key"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("课程负责人");
        assertThatThrownBy(() -> service.requireMineruToken(9L, "你还没有配置 MinerU 令牌"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("MinerU");
    }

    @Test
    void 空密钥_400() {
        assertThatThrownBy(() -> service.updateLlmKey("   "))
                .isInstanceOf(BadRequestException.class);
    }
}
