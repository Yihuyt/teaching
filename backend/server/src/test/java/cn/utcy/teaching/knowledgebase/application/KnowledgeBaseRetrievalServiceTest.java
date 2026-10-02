package cn.utcy.teaching.knowledgebase.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.ai.llm.LlmCalls;
import cn.utcy.teaching.knowledgebase.infrastructure.EsClient;
import cn.utcy.teaching.knowledgebase.infrastructure.KbChunkEntity;
import cn.utcy.teaching.knowledgebase.infrastructure.KbChunkMapper;
import cn.utcy.teaching.knowledgebase.infrastructure.KbDocumentEntity;
import cn.utcy.teaching.knowledgebase.infrastructure.KbDocumentMapper;
import cn.utcy.teaching.knowledgebase.infrastructure.KnowledgeBaseEntity;
import cn.utcy.teaching.knowledgebase.infrastructure.KnowledgebaseProperties;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseRetrievalServiceTest {

    private final KnowledgeBaseService knowledgeBases = mock(KnowledgeBaseService.class);
    private final KbDocumentMapper documents = mock(KbDocumentMapper.class);
    private final KbChunkMapper chunks = mock(KbChunkMapper.class);
    private final EsClient es = mock(EsClient.class);
    private final LlmCalls llm = mock(LlmCalls.class);
    private final CourseAiKeys aiKeys = mock(CourseAiKeys.class);
    private final KnowledgebaseProperties properties = mock(KnowledgebaseProperties.class);

    private KnowledgeBaseRetrievalService service() {
        // LambdaQueryWrapper 需要实体的列缓存(单测无 Spring 上下文,手动初始化)
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), KbChunkEntity.class);
        when(properties.embeddingModel()).thenReturn("text-embedding-v4");
        when(properties.embeddingDimension()).thenReturn(4);
        when(knowledgeBases.currentSignature()).thenReturn("sig1");
        return new KnowledgeBaseRetrievalService(knowledgeBases, documents, chunks, es, llm, aiKeys,
                properties);
    }

    private KnowledgeBaseEntity kb(String signature) {
        KnowledgeBaseEntity entity = mock(KnowledgeBaseEntity.class);
        when(entity.getId()).thenReturn(1L);
        when(entity.getName()).thenReturn("光学知识库");
        when(entity.getActiveSignature()).thenReturn(signature);
        when(entity.getActiveIndexName()).thenReturn("kb-1-" + signature);
        return entity;
    }

    @Test
    void 索引未就绪明确抛冲突() {
        KnowledgeBaseEntity stale = kb("stale");
        when(knowledgeBases.require(6L, 1L)).thenReturn(stale);

        assertThatThrownBy(() -> service().requireReady(6L, List.of(1L)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("索引未就绪");
    }

    @Test
    void 按RRF顺序回填正文() {
        KnowledgeBaseEntity ready = kb("sig1");
        when(knowledgeBases.require(6L, 1L)).thenReturn(ready);
        when(aiKeys.llmKeyForCourse(6L)).thenReturn("key");
        when(llm.embed(anyString(), anyString(), anyInt(), any()))
                .thenReturn(List.of(new float[] {0.1f, 0.2f, 0.3f, 0.4f}));
        // BM25 与 kNN 都把 10-2 排第一,10-1 排第二
        when(es.searchBm25(eq("kb-1-sig1"), anyString(), anyInt())).thenReturn(List.of("10-2", "10-1"));
        when(es.searchKnn(eq("kb-1-sig1"), any(), anyInt())).thenReturn(List.of("10-2", "10-1"));
        KbDocumentEntity document = mock(KbDocumentEntity.class);
        when(document.getId()).thenReturn(10L);
        when(document.getName()).thenReturn("讲义.md");
        when(documents.selectByIds(any())).thenReturn(List.of(document));
        KbChunkEntity first = mock(KbChunkEntity.class);
        when(first.getSeq()).thenReturn(1);
        when(first.getSection()).thenReturn("入射");
        when(first.getContent()).thenReturn("入射角…");
        KbChunkEntity second = mock(KbChunkEntity.class);
        when(second.getSeq()).thenReturn(2);
        when(second.getSection()).thenReturn("反射");
        when(second.getContent()).thenReturn("反射角等于入射角");
        when(chunks.selectList(any())).thenReturn(List.of(first, second));

        List<KnowledgeBaseRetrievalService.RetrievedPassage> passages =
                service().retrieve(6L, 1L, "反射定律", 5);

        assertThat(passages).extracting(KnowledgeBaseRetrievalService.RetrievedPassage::section)
                .containsExactly("反射", "入射");
        assertThat(KnowledgeBaseRetrievalService.renderContext(passages))
                .startsWith("[source-1] 讲义.md › 反射\n反射角等于入射角");
    }
}
