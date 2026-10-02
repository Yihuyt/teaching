package cn.utcy.teaching.blockcoding.infrastructure;

import cn.utcy.teaching.ai.llm.ModelSettings;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("teaching.blockcoding")
public record BlockCodingProperties(
        @NotBlank String model,
        double temperature,
        double topP,
        int maxOutputTokens,
        /** 模型上下文窗口(token),智能体循环据此裁剪旧工具结果 */
        @Positive int contextWindow
)implements ModelSettings {
    /** 不开思考:助手靠 tool_choice 强制模型收尾,百炼的思考模式不支持强制工具 */
    @Override
    public boolean reasoning() {
        return false;
    }
}
