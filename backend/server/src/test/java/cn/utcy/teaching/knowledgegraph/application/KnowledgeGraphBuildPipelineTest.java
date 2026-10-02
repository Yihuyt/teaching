package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.util.BoundedParallel;
import cn.utcy.teaching.ai.document.MineruClient;
import cn.utcy.teaching.ai.document.MineruClient.MineruUnavailableException;
import cn.utcy.teaching.shared.sse.ProgressBus;
import cn.utcy.teaching.knowledgegraph.domain.BuildPreview;
import cn.utcy.teaching.knowledgegraph.domain.BuildStatus;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphBuildEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphBuildMapper;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService.DownloadTicket;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeGraphBuildPipelineTest {

    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);

    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeGraphBuildMapper builds = mock(KnowledgeGraphBuildMapper.class);
    private final BuildSectionMapper sections = mock(BuildSectionMapper.class);
    private final TocRecognitionService tocRecognition = mock(TocRecognitionService.class);
    private final GraphExtractionService extraction = mock(GraphExtractionService.class);
    private final MineruClient mineru = mock(MineruClient.class);
    private final CourseMaterialApplicationService materials = mock(CourseMaterialApplicationService.class);
    private final CourseAiKeys aiKeys = mock(CourseAiKeys.class);
    private final ProgressBus bus = new ProgressBus(json);

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeGraphBuildEntity.class);
        TableInfoHelper.initTableInfo(assistant, BuildSectionEntity.class);
    }

    /** 执行器同步跑:start 返回时本次运行已经收尾 */
    private KnowledgeGraphBuildPipeline pipeline() {
        when(builds.updateById(any(KnowledgeGraphBuildEntity.class))).thenReturn(1);
        when(builds.beat(anyLong(), anyString(), any())).thenReturn(1);
        when(aiKeys.llmKeyForCourse(6L)).thenReturn("key");
        when(aiKeys.mineruTokenForCourse(6L)).thenReturn("token");
        when(materials.createDownloadTrusted(6L, 3L)).thenReturn(new DownloadTicket("https://oss/x", NOW));
        when(tocRecognition.recognize(anyString(), any(), any()))
                .thenReturn(new TocRecognitionService.TocDraft(false, List.of(1), List.of(), List.of()));
        return new KnowledgeGraphBuildPipeline(builds, sections, tocRecognition, extraction, mineru, materials,
                aiKeys, KnowledgeGraphBuildServiceTest.PROPERTIES,
                new BuildDocuments(json, KnowledgeGraphBuildServiceTest.PROPERTIES), bus,
                Runnable::run, TransactionOperations.withoutTransaction(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private KnowledgeGraphBuildEntity active(long id, BuildStatus status) {
        KnowledgeGraphBuildEntity build = KnowledgeGraphBuildServiceTest.entity(id, status);
        when(builds.selectForUpdate(id)).thenReturn(build);
        when(builds.selectById(id)).thenReturn(build);
        return build;
    }

    @Test
    @DisplayName("解析直至目录就绪:run 标记收尾清空")
    void parseCompletesToTocReady() {
        KnowledgeGraphBuildEntity build = active(1L, BuildStatus.PARSING);
        when(mineru.parseToPages(anyString(), anyString(), anyInt(), any(), any(), any())).thenReturn(List.of("正文"));

        pipeline().start(1L, BuildStatus.PARSING, "run-token");

        assertThat(build.getStatus()).isEqualTo(BuildStatus.TOC_READY);
        assertThat(build.getRunToken()).isNull();
        assertThat(build.getProgressHeartbeatAt()).isNull();
    }

    @Test
    @DisplayName("MinerU 任务登记落在构建行上:重试续跑能查到上次的任务 id,新提交的即时落库")
    void mineruLedgerPersistsOnBuild() {
        KnowledgeGraphBuildEntity build = active(1L, BuildStatus.PARSING);
        build.rememberMineruTasks("{\"1-200\":\"t-1\"}", NOW_LOCAL);
        when(mineru.parseToPages(anyString(), anyString(), anyInt(), any(), any(), any())).thenAnswer(invocation -> {
            MineruClient.TaskLedger ledger = invocation.getArgument(3);
            assertThat(ledger.taskId("1-200")).isEqualTo("t-1");
            assertThat(ledger.taskId("201-400")).isNull();
            ledger.record("201-400", "t-2");
            assertThat(build.getMineruTasksJson()).isEqualTo("{\"1-200\":\"t-1\",\"201-400\":\"t-2\"}");
            return List.of("正文");
        });

        pipeline().start(1L, BuildStatus.PARSING, "run-token");

        assertThat(build.getStatus()).isEqualTo(BuildStatus.TOC_READY);
        assertThat(build.getMineruTasksJson()).isNull();
    }

    @Test
    @DisplayName("取消标记经进展心跳观察:管线在下一个安全边界停下,以「任务已取消」收尾")
    void cancelObservedViaHeartbeat() {
        KnowledgeGraphBuildEntity build = active(1L, BuildStatus.PARSING);
        when(builds.cancelRequested(1L)).thenReturn(true);
        when(mineru.parseToPages(anyString(), anyString(), anyInt(), any(), any(), any())).thenAnswer(invocation -> {
            BooleanSupplier stop = invocation.getArgument(5);
            if (stop.getAsBoolean()) {
                throw new MineruUnavailableException("解析已取消");
            }
            return List.of("正文");
        });

        pipeline().start(1L, BuildStatus.PARSING, "run-token");

        assertThat(build.getStatus()).isEqualTo(BuildStatus.FAILED);
        assertThat(build.getErrorMessage()).isEqualTo(BoundedParallel.CANCELLED);
        verify(tocRecognition, never()).recognize(anyString(), any(), any());
    }

    @Test
    @DisplayName("写围栏:run 标记已不是本次的(被判滞巡检收尾/新启动接手),结果作废、行不动")
    void lostTokenDiscardsResult() {
        KnowledgeGraphBuildEntity build = active(1L, BuildStatus.PARSING);
        KnowledgeGraphBuildPipeline pipeline = pipeline();
        when(builds.beat(anyLong(), anyString(), any())).thenReturn(0); // 在助手 stub 之后覆盖
        when(mineru.parseToPages(anyString(), anyString(), anyInt(), any(), any(), any())).thenAnswer(invocation -> {
            BooleanSupplier stop = invocation.getArgument(5);
            if (stop.getAsBoolean()) {
                throw new MineruUnavailableException("解析已取消");
            }
            return List.of("正文");
        });

        pipeline.start(1L, BuildStatus.PARSING, "run-token");

        assertThat(build.getStatus()).isEqualTo(BuildStatus.PARSING);
        assertThat(build.getTocDraftJson()).isNull();
        assertThat(build.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("运行异常:如实判失败,原因进行文案")
    void exceptionFailsTheBuild() {
        KnowledgeGraphBuildEntity build = active(1L, BuildStatus.PARSING);
        when(mineru.parseToPages(anyString(), anyString(), anyInt(), any(), any(), any()))
                .thenThrow(new IllegalStateException("MinerU 服务不可用"));

        pipeline().start(1L, BuildStatus.PARSING, "run-token");

        assertThat(build.getStatus()).isEqualTo(BuildStatus.FAILED);
        assertThat(build.getErrorMessage()).isEqualTo("MinerU 服务不可用");
    }

    @Test
    @DisplayName("抽取:小节全部完成或已忽略时不再抽,直接合并出预览")
    @SuppressWarnings("unchecked")
    void extractMergesWhenAllSectionsSettled() {
        KnowledgeGraphBuildEntity build = active(1L, BuildStatus.EXTRACTING);
        BuildSectionEntity done = new BuildSectionEntity(1L, 0, 0, "", "成功节", "章", 1, 1);
        done.done("{}", "{\"knowledgePoints\":[]}");
        when(sections.selectList(any(Wrapper.class))).thenReturn(List.of(done));
        when(extraction.finalizeGraph(anyString(), anyString(), any(), any(), any(), any()))
                .thenReturn(new BuildPreview(List.of(), List.of(), List.of()));

        pipeline().start(1L, BuildStatus.EXTRACTING, "run-token");

        assertThat(build.getStatus()).isEqualTo(BuildStatus.EXTRACTED);
        verify(extraction, never()).extractSections(anyString(), anyString(), any(), any(), any(), any());
    }
}
