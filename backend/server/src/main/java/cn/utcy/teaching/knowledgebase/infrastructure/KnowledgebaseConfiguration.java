package cn.utcy.teaching.knowledgebase.infrastructure;

import cn.utcy.teaching.knowledgebase.domain.Chunker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.VirtualThreadTaskExecutor;

@Configuration
public class KnowledgebaseConfiguration {

    /** 入库/重建执行器:虚拟线程,任务几乎都在等 MinerU/向量化,不占真实线程,不设平台侧并发上限 */
    @Bean("kbIngestExecutor")
    public TaskExecutor kbIngestExecutor() {
        return new VirtualThreadTaskExecutor("kb-ingest-");
    }

    @Bean
    public Chunker kbChunker(KnowledgebaseProperties properties) {
        return new Chunker(properties.chunkTargetChars(), properties.chunkMaxChars(),
                properties.chunkOverlapChars());
    }
}
