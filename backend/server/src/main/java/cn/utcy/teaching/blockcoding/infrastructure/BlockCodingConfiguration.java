package cn.utcy.teaching.blockcoding.infrastructure;

import cn.utcy.teaching.ai.llm.LlmCalls;
import cn.utcy.teaching.blockcoding.application.agent.SkillCatalog;
import cn.utcy.teaching.blockcoding.application.agent.ScratchAgentPrompt;
import cn.utcy.teaching.blockcoding.application.agent.ScratchProgramAgent;
import cn.utcy.teaching.blockcoding.application.agent.ScratchTools;
import cn.utcy.teaching.blockcoding.engine.SbEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
class BlockCodingConfiguration {
    @Bean("blockcodingSkills")
    SkillCatalog blockcodingSkills() {
        return new SkillCatalog("blockcoding/skills");
    }

    @Bean
    ScratchTools scratchTools(SbEngine engine, ObjectMapper objectMapper) {
        return new ScratchTools(engine, blockcodingSkills(), objectMapper);
    }

    @Bean
    ScratchProgramAgent scratchProgramAgent(ScratchTools tools, BlockCodingProperties properties,
                                            ObjectMapper objectMapper, LlmCalls llm) {
        return new ScratchProgramAgent(tools, new ScratchAgentPrompt(blockcodingSkills()), objectMapper,
                Executors.newVirtualThreadPerTaskExecutor(), llm, properties.contextWindow());
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService blockCodingStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
