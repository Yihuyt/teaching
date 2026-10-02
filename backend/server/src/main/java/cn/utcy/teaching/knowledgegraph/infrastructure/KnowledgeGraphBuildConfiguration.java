package cn.utcy.teaching.knowledgegraph.infrastructure;

import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.structured.SchemaRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;

import java.util.List;

@Configuration
public class KnowledgeGraphBuildConfiguration {

    /**
     * 构建与模型调用共用的执行器:虚拟线程,阻塞等待外部服务不占真实线程;
     * 并发不由线程数决定,由直启的构建数与每次并行的许可数决定。
     */
    @Bean("kgTaskExecutor")
    public TaskExecutor kgTaskExecutor() {
        return new VirtualThreadTaskExecutor("kg-");
    }

    @Bean("knowledgegraphSchemas")
    public SchemaRegistry knowledgegraphSchemas(ObjectMapper objectMapper) {
        return new SchemaRegistry(objectMapper, "knowledgegraph/schemas",
                List.of("toc-detect", "toc-parse", "summarize", "kg-extract", "entity-merge"));
    }

    @Bean("knowledgegraphLlmModel")
    public ModelConfig knowledgegraphLlmModel(KnowledgegraphProperties properties) {
        return ModelConfig.of("knowledgegraph", properties);
    }
}
