package cn.utcy.teaching.shared.sse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;

/**
 * SSE 长任务(AI 生成/TTS)专用执行器,与 Tomcat 工作线程隔离,各 AI 消费模块共享。
 * 每任务一个虚拟线程:AI 功能不设平台侧并发上限,外部模型的限流即自然背压。
 */
@Configuration
public class SseTaskExecutorConfiguration {

    @Bean("sseTaskExecutor")
    public TaskExecutor sseTaskExecutor() {
        return new VirtualThreadTaskExecutor("sse-task-");
    }
}
