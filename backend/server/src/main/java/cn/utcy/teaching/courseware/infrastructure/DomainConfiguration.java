package cn.utcy.teaching.courseware.infrastructure;

import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.structured.SchemaRegistry;
import cn.utcy.teaching.courseware.domain.layout.LayoutEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 纯域组件与模块自持 AI 配置的装配点(域类零 Spring 依赖,在此注册为 bean) */
@Configuration
public class DomainConfiguration {

    @Bean
    public LayoutEngine layoutEngine() {
        return new LayoutEngine();
    }

    /** 课件模块的 JSON Schema 目录(资源缺失启动即炸);编辑操作的 stage-ops 定义由 EditOpSchema 装载并编译 */
    @Bean("coursewareSchemas")
    public SchemaRegistry coursewareSchemas(ObjectMapper objectMapper) {
        return new SchemaRegistry(objectMapper, "courseware/schemas",
                List.of("outline", "scene-blocks", "speech", "answer"));
    }

    @Bean("coursewareLlmModel")
    public ModelConfig coursewareLlmModel(CoursewareProperties properties) {
        return ModelConfig.of("courseware", properties);
    }
}
