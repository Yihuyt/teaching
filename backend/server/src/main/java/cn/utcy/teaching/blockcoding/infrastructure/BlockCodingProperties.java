package cn.utcy.teaching.blockcoding.infrastructure;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("teaching.blockcoding")
public record BlockCodingProperties(
        double temperature,
        double topP,
        int maxOutputTokens,
        /** 模型上下文窗口(token),智能体循环据此裁剪旧工具结果 */
        @Positive int contextWindow
) {
}
