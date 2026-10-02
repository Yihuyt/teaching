package cn.utcy.teaching.tutor.infrastructure;

import cn.utcy.teaching.ai.llm.ModelSettings;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 智能问答的生成配置(默认:温度 0.2、8 轮、单轮 8000 tokens)。
 * contextWindow 用于上下文裁剪护栏与历史预算(token 按字符启发式估算)。
 */
@Validated
@ConfigurationProperties("teaching.tutor")
public record TutorProperties(
        @NotBlank String model,
        double temperature,
        double topP,
        int maxOutputTokens,
        boolean reasoning,
        @Min(1) int maxRounds,
        @Min(1024) int contextWindow
)implements ModelSettings {
}
