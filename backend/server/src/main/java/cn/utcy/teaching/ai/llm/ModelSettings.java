package cn.utcy.teaching.ai.llm;

/** 各功能模块配置里的模型参数段;配置记录只要有同名访问器就天然满足 */
public interface ModelSettings {
    String model();

    boolean reasoning();

    double temperature();

    double topP();

    int maxOutputTokens();
}
