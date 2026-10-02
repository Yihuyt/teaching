package cn.utcy.teaching.courseware.infrastructure;

import cn.utcy.teaching.ai.llm.ModelSettings;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 智能课堂(课件)的模型配置:大模型生成参数、语音音色、文生图模型、视觉能力开关。
 * 密钥与端点归 ai 模块统一持有,此处只声明"用哪个模型、怎么生成"。
 */
@Validated
@ConfigurationProperties("teaching.courseware")
public record CoursewareProperties(
        @NotBlank String model,
        double temperature,
        double topP,
        int maxOutputTokens,
        boolean reasoning,
        @NotBlank String ttsModel,
        @NotBlank String ttsVoice,
        @NotBlank String imageModel,
        /* 生成模型是否具备视觉能力:为真时素材图片作为多模态输入附给逐页生成 */
        boolean vision
) implements ModelSettings {
}
