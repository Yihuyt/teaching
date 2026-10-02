package cn.utcy.teaching.ai.infrastructure;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * MinerU 云端解析的平台侧参数(令牌本身是用户级配置,不在这里)。
 *
 * @param baseUrl MinerU 云端 API 根地址(测试可指向本地桩)
 */
@Validated
@ConfigurationProperties("teaching.ai.mineru")
public record AiMineruProperties(
        @NotBlank String baseUrl
) {
}
