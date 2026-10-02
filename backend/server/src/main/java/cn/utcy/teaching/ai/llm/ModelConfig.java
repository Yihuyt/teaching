package cn.utcy.teaching.ai.llm;

/**
 * 一个可调用大模型的完整生成配置。目录由各业务模块自持,生成参数(温度/top_p/max_tokens/思考开关)不外泄给前端。
 */
public record ModelConfig(
        String id,
        String providerModel,
        boolean reasoning,
        double temperature,
        double topP,
        int maxOutputTokens
) {

    public static ModelConfig of(String id, ModelSettings settings) {
        return new ModelConfig(id, settings.model(), settings.reasoning(), settings.temperature(),
                settings.topP(), settings.maxOutputTokens());
    }
}
