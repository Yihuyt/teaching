package cn.utcy.teaching.question.infrastructure;

import cn.utcy.teaching.ai.llm.ModelSettings;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI 出题的大模型生成参数。密钥与端点归 ai 模块统一持有,
 * 此处只声明"用哪个模型、怎么生成"。温度高于课件(出题要多样性)。
 */
@Validated
@ConfigurationProperties("teaching.question")
public record QuestionProperties(
        @NotBlank String model,
        double temperature,
        double topP,
        int maxOutputTokens,
        boolean reasoning
)implements ModelSettings {
}
