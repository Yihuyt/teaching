package cn.utcy.teaching.knowledgebase.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.ai.llm.LlmCalls;
import cn.utcy.teaching.ai.document.DocumentParser;
import cn.utcy.teaching.shared.sse.ProgressBus;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.knowledgebase.domain.Chunker;
import cn.utcy.teaching.knowledgebase.domain.DocumentState;
import cn.utcy.teaching.knowledgebase.infrastructure.EsClient;
import cn.utcy.teaching.knowledgebase.infrastructure.KbChunkEntity;
import cn.utcy.teaching.knowledgebase.infrastructure.KbChunkMapper;
import cn.utcy.teaching.knowledgebase.infrastructure.KbDocumentEntity;
import cn.utcy.teaching.knowledgebase.infrastructure.KbDocumentMapper;
import cn.utcy.teaching.knowledgebase.infrastructure.KnowledgeBaseEntity;
import cn.utcy.teaching.knowledgebase.infrastructure.KnowledgeBaseMapper;
import cn.utcy.teaching.knowledgebase.infrastructure.KnowledgebaseProperties;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService.DownloadTicket;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService.MaterialView;
import cn.utcy.teaching.resource.domain.MaterialKind;
import cn.utcy.teaching.resource.domain.MaterialState;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 入库/重建:物理索引指针(首建抢占、重建先建后切、失败不动指针)、重建与入库互斥、
 * 在途上限、显式中止。执行器可控:任务先积压,测试自行决定何时跑。
 */
class DocumentIngestServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.ofInstant(
            Instant.parse("2026-08-31T00:00:00Z"), ZoneOffset.UTC);

    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
    private final KnowledgeBaseMapper knowledgeBases = mock(KnowledgeBaseMapper.class);
    private final KbDocumentMapper documents = mock(KbDocumentMapper.class);
    private final KbChunkMapper chunks = mock(KbChunkMapper.class);
    private final EsClient es = mock(EsClient.class);
    private final DocumentParser parser = mock(DocumentParser.class);
    private final LlmCalls llm = mock(LlmCalls.class);
    private final CourseAiKeys aiKeys = mock(CourseAiKeys.class);
    private final CourseMaterialApplicationService materials = mock(CourseMaterialApplicationService.class);
    private final KnowledgebaseProperties properties = mock(KnowledgebaseProperties.class);
    private final PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
    private final List<Runnable> queued = new ArrayList<>();

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeBaseEntity.class);
        TableInfoHelper.initTableInfo(assistant, KbDocumentEntity.class);
        TableInfoHelper.initTableInfo(assistant, KbChunkEntity.class);
    }

    @BeforeEach
    void stubDefaults() {
        when(properties.embeddingModel()).thenReturn("text-embedding-v4");
        when(properties.embeddingDimension()).thenReturn(4);
        when(properties.embeddingBatchSize()).thenReturn(10);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(knowledgeBaseService.currentSignature()).thenReturn("sig1");
        when(documents.updateById(any(KbDocumentEntity.class))).thenReturn(1);
        when(documents.update(any(KbDocumentEntity.class), any())).thenReturn(1);
        when(documents.beat(anyLong(), anyString(), any())).thenReturn(1);
        when(documents.selectForUpdate(42L)).thenReturn(new KbDocumentEntity(1L, 3L, "讲义.md", NOW));
        // 生产环境由 MyBatis-Plus 回填自增 id;测试里手动回填,通道键 / 中止都靠它
        when(documents.insert(any(KbDocumentEntity.class))).thenAnswer(invocation -> {
            setId(invocation.getArgument(0), 42L);
            return 1;
        });
        when(chunks.insert(any(KbChunkEntity.class))).thenReturn(1);
        when(knowledgeBases.updateById(any(KnowledgeBaseEntity.class))).thenReturn(1);
        when(aiKeys.llmKeyForCourse(6L)).thenReturn("key");
        when(llm.embed(anyString(), anyString(), anyInt(), any()))
                .thenAnswer(invocation -> {
                    List<String> texts = invocation.getArgument(3);
                    return texts.stream().map(text -> new float[] {0.1f, 0.2f, 0.3f, 0.4f}).toList();
                });
    }

    private DocumentIngestService service() {
        return new DocumentIngestService(knowledgeBaseService, knowledgeBases, documents, chunks,
                es, parser, new Chunker(800, 1200, 80), llm, aiKeys, materials,
                mock(CourseAccess.class), mock(CurrentActor.class), properties, json,
                new ProgressBus(json), queued::add, transactionManager, Clock.systemUTC());
    }

    private void runQueued() {
        List<Runnable> tasks = new ArrayList<>(queued);
        queued.clear();
        tasks.forEach(Runnable::run);
    }

    private KnowledgeBaseEntity kb(String signature, String indexName) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(6L, "光学知识库", NOW);
        if (signature != null) {
            entity.activateIndex(signature, indexName, NOW);
        }
        setId(entity, 1L);
        when(knowledgeBaseService.require(6L, 1L)).thenReturn(entity);
        when(knowledgeBases.selectById(1L)).thenReturn(entity);
        return entity;
    }

    private MaterialView material() {
        MaterialView view = new MaterialView(3L, 6L, null, "讲义.md", MaterialKind.FILE,
                "text/markdown", 10L, "sha", MaterialState.ACTIVE,
                Instant.parse("2026-08-31T00:00:00Z"), Instant.parse("2026-08-31T00:00:00Z"));
        when(materials.getTrusted(6L, 3L)).thenReturn(view);
        when(materials.createDownloadForManagement(6L, 3L))
                .thenReturn(new DownloadTicket("https://oss/x", Instant.parse("2026-08-31T00:00:00Z")));
        return view;
    }

    @Test
    @DisplayName("首份文档:建物理索引并抢占指针,入库完成文档就绪")
    void firstIngestClaimsPointer() {
        kb(null, null);
        material();
        when(knowledgeBases.activateIndexIfEmpty(eq(1L), eq("sig1"), startsWith("kb-1-sig1-"))).thenReturn(1);
        when(parser.parse(anyString(), anyString(), any(), any(), any())).thenReturn("# 一\n光沿直线传播。");

        DocumentIngestService service = service();
        var view = service.ingest(6L, 1L, 3L);
        runQueued();

        verify(es).createIndex(startsWith("kb-1-sig1-"), eq(4));
        verify(es).bulkIndex(startsWith("kb-1-sig1-"), any());
        var captor = org.mockito.ArgumentCaptor.forClass(KbDocumentEntity.class);
        verify(documents, org.mockito.Mockito.atLeastOnce()).update(captor.capture(), any());
        assertThat(captor.getValue().getState()).isEqualTo(DocumentState.READY);
        assertThat(view.state()).isEqualTo(DocumentState.PENDING);
    }

    @Test
    @DisplayName("并发首建输掉指针:删掉自己的索引,改写赢家的索引")
    void firstIngestLosesPointerRace() {
        KnowledgeBaseEntity entity = kb(null, null);
        material();
        when(knowledgeBases.activateIndexIfEmpty(anyLong(), anyString(), anyString())).thenReturn(0);
        // 输掉抢占后重读:赢家已把指针写好
        KnowledgeBaseEntity winner = new KnowledgeBaseEntity(6L, "光学知识库", NOW);
        winner.activateIndex("sig1", "kb-1-sig1-winner", NOW);
        setId(winner, 1L);
        when(knowledgeBases.selectById(1L)).thenReturn(entity).thenReturn(winner);
        when(parser.parse(anyString(), anyString(), any(), any(), any())).thenReturn("光沿直线传播。");

        service().ingest(6L, 1L, 3L);
        runQueued();

        verify(es).deleteIndexIfExists(startsWith("kb-1-sig1-"));
        verify(es).bulkIndex(eq("kb-1-sig1-winner"), any());
    }

    @Test
    @DisplayName("重建:写新物理索引,成功才切指针,之后清旧索引;在用索引全程未被动过")
    void rebuildSwapsPointerThenCleansOld() {
        KnowledgeBaseEntity entity = kb("old-sig", "kb-1-old");
        KbDocumentEntity ready = readyDocument();
        when(documents.selectList(any(Wrapper.class))).thenReturn(List.of(ready));
        when(documents.exists(any(Wrapper.class))).thenReturn(false);
        KbChunkEntity chunk = new KbChunkEntity(5L, 0, "第一章", "光沿直线传播。");
        when(chunks.selectList(any(Wrapper.class))).thenReturn(List.of(chunk));

        DocumentIngestService service = service();
        var started = service.rebuild(6L, 1L);
        assertThat(started.documentCount()).isEqualTo(1);
        runQueued();

        verify(es).createIndex(startsWith("kb-1-sig1-"), eq(4));
        verify(es).bulkIndex(startsWith("kb-1-sig1-"), any());
        assertThat(entity.getActiveSignature()).isEqualTo("sig1");
        assertThat(entity.getActiveIndexName()).startsWith("kb-1-sig1-");
        verify(es).deleteIndexIfExists("kb-1-old");
    }

    @Test
    @DisplayName("重建失败:删掉新索引,指针与在用索引原样保留")
    void rebuildFailureKeepsActiveIndex() {
        KnowledgeBaseEntity entity = kb("sig1", "kb-1-live");
        KbDocumentEntity ready = readyDocument();
        when(documents.selectList(any(Wrapper.class))).thenReturn(List.of(ready));
        when(documents.exists(any(Wrapper.class))).thenReturn(false);
        when(chunks.selectList(any(Wrapper.class))).thenReturn(
                List.of(new KbChunkEntity(5L, 0, "第一章", "光沿直线传播。")));
        org.mockito.Mockito.doThrow(new RuntimeException("限流"))
                .when(llm).embed(anyString(), anyString(), anyInt(), any());

        service().rebuild(6L, 1L);
        runQueued();

        assertThat(entity.getActiveIndexName()).isEqualTo("kb-1-live");
        assertThat(entity.getActiveSignature()).isEqualTo("sig1");
        verify(es).deleteIndexIfExists(startsWith("kb-1-sig1-"));
        verify(es, never()).deleteIndexIfExists("kb-1-live");
    }

    @Test
    @DisplayName("重建与入库互斥:重建在途时添加文档 409;有文档在入库时重建 409")
    void rebuildAndIngestAreMutuallyExclusive() {
        kb("sig1", "kb-1-live");
        material();
        when(documents.selectList(any(Wrapper.class))).thenReturn(List.of(readyDocument()));
        when(documents.exists(any(Wrapper.class))).thenReturn(false);

        DocumentIngestService service = service();
        service.rebuild(6L, 1L); // 任务积压未跑,登记在途
        assertThatThrownBy(() -> service.ingest(6L, 1L, 3L))
                .isInstanceOf(ConflictException.class).hasMessageContaining("正在重建索引");
        runQueued();

        // 有文档处于运行态(如重启遗留):拒绝重建
        when(documents.exists(any(Wrapper.class))).thenAnswer(invocation -> true);
        assertThatThrownBy(() -> service.rebuild(6L, 1L))
                .isInstanceOf(ConflictException.class).hasMessageContaining("有文档正在入库");
    }

    @Test
    @DisplayName("显式中止:任务在安全边界停下,文档标失败可重试")
    void cancelStopsAtSafeBoundary() {
        kb("sig1", "kb-1-live");
        material();
        when(documents.exists(any(Wrapper.class))).thenReturn(false);
        when(parser.parse(anyString(), anyString(), any(), any(), any())).thenReturn("光沿直线传播。");
        when(knowledgeBaseService.requireDocument(1L, 42L)).thenReturn(new KbDocumentEntity(1L, 3L, "讲义.md", NOW));

        DocumentIngestService service = service();
        var view = service.ingest(6L, 1L, 3L);
        assertThat(view.id()).isEqualTo(42L);
        service.cancelDocument(6L, 1L, 42L);
        runQueued();

        var captor = org.mockito.ArgumentCaptor.forClass(KbDocumentEntity.class);
        verify(documents, org.mockito.Mockito.atLeastOnce()).update(captor.capture(), any());
        assertThat(captor.getValue().getState()).isEqualTo(DocumentState.ERROR);
        assertThat(captor.getValue().getErrorMessage()).contains("中止");
        verify(es, never()).bulkIndex(anyString(), any());
    }

    private static void setId(Object entity, long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private KbDocumentEntity readyDocument() {
        KbDocumentEntity document = new KbDocumentEntity(1L, 3L, "讲义.md", NOW);
        document.markReady(1, "old-sig", NOW);
        setId(document, 5L);
        return document;
    }
}
