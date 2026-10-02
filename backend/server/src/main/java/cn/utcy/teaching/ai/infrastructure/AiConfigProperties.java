package cn.utcy.teaching.ai.infrastructure;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * 用户级 AI 配置的平台侧参数。
 *
 * @param keyPath 用户密钥的落库加密密钥文件(32 字节 base64;部署经 docker secret 挂载)
 */
@Validated
@ConfigurationProperties("teaching.ai.config")
public record AiConfigProperties(@NotNull Path keyPath) {
}
