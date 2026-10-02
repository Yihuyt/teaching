package cn.utcy.teaching.tutor.infrastructure;

import cn.utcy.teaching.ai.llm.ModelConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class TutorConfiguration {

    @Bean("tutorLlmModel")
    public ModelConfig tutorLlmModel(TutorProperties properties) {
        return ModelConfig.of("tutor", properties);
    }

    /** 问答回合执行器:虚拟线程,回合是纯 I/O 等待(模型流式返回),不占真实线程,不设平台侧并发上限 */
    @Bean("tutorChatExecutor")
    public TaskExecutor tutorChatExecutor() {
        return new VirtualThreadTaskExecutor("tutor-chat-");
    }

    /** 一轮内并行工具调用的执行器(虚拟线程,I/O 等待为主) */
    @Bean("tutorToolExecutor")
    public Executor tutorToolExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
