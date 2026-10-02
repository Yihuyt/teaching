package cn.utcy.teaching.question.infrastructure;

import cn.utcy.teaching.ai.llm.ModelConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class QuestionAiConfiguration {

    /** 出题循环内工具调用的执行器(虚拟线程,I/O 等待为主) */
    @Bean("questionToolExecutor")
    public java.util.concurrent.Executor questionToolExecutor() {
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean("questionLlmModel")
    public ModelConfig questionLlmModel(QuestionProperties properties) {
        return ModelConfig.of("question", properties);
    }
}
