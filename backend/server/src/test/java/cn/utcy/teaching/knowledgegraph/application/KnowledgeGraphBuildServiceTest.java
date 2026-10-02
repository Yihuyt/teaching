package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.util.BoundedParallel;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.knowledgegraph.domain.BuildPreview;
import cn.utcy.teaching.knowledgegraph.domain.BuildStatus;
import cn.utcy.teaching.knowledgegraph.domain.NodeKind;
import cn.utcy.teaching.knowledgegraph.domain.SectionSlicer;
import cn.utcy.teaching.knowledgegraph.domain.SectionStatus;
import cn.utcy.teaching.knowledgegraph.domain.TocEntry;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphBuildEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphBuildMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphRowPurger;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgegraphProperties;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService.DownloadTicket;
import cn.utcy.teaching.shared.sse.ProgressBus;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeGraphBuildServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    static final KnowledgegraphProperties PROPERTIES = new KnowledgegraphProperties("test", 0.2, 0.9, 8192, false,
            24000, 8_000_000, 800, "text-embedding-v4", 4,
            Duration.ofMinutes(20));

    private final ObjectMapper json = new ObjectMapper();
    private final KnowledgeGraphBuildMapper builds = mock(KnowledgeGraphBuildMapper.class);
    private final BuildSectionMapper sections = mock(BuildSectionMapper.class);
    private final CourseAiKeys aiKeys = mock(CourseAiKeys.class);
    private final CourseMaterialApplicationService materials = mock(CourseMaterialApplicationService.class);
    private final KnowledgeGraphEditor editor = mock(KnowledgeGraphEditor.class);
    private final KnowledgeGraphRowPurger purger = mock(KnowledgeGraphRowPurger.class);
    private final KnowledgeGraphBuildPipeline pipeline = mock(KnowledgeGraphBuildPipeline.class);
    private final ProgressBus bus = new ProgressBus(json);

    @BeforeAll
    static void initializeMybatisMetadata() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, KnowledgeGraphBuildEntity.class);
        TableInfoHelper.initTableInfo(assistant, BuildSectionEntity.class);
    }

    private KnowledgeGraphBuildService service() {
        when(aiKeys.llmKeyForCourse(6L)).thenReturn("key");
        when(aiKeys.mineruTokenForCourse(6L)).thenReturn("token");
        when(builds.updateById(any(KnowledgeGraphBuildEntity.class))).thenReturn(1);
        return new KnowledgeGraphBuildService(builds, sections, editor, aiKeys, materials,
                mock(CourseAccess.class), mock(CurrentActor.class), PROPERTIES, json,
                new BuildDocuments(json, PROPERTIES), bus, purger, pipeline,
                TransactionOperations.withoutTransaction(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static KnowledgeGraphBuildEntity entity(long id, BuildStatus status) {
        LocalDateTime earlier = NOW_LOCAL.minusHours(1);
        KnowledgeGraphBuildEntity build = new KnowledgeGraphBuildEntity(6L, 3L, "教材.pdf", 7L, "run-token", earlier);
        if (status != BuildStatus.PARSING) {
            build.tocReady("[\"第一页\"]", "{\"entries\":[]}", earlier);
            build.confirmToc("{\"entries\":[{\"number\":\"\",\"title\":\"章\",\"level\":1,\"page\":1,\"endPage\":1}]}", earlier);
        }
        switch (status) {
            case EXTRACTING -> build.extracting("run-token", earlier);
            case EXTRACTED -> build.extracted("{\"nodes\":[],\"edges\":[],\"warnings\":[]}", earlier);
            case FAILED -> build.failed("原因", earlier);
            default -> {
            }
        }
        try {
            var field = KnowledgeGraphBuildEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(build, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        return build;
    }

    private KnowledgeGraphBuildEntity build(BuildStatus status) {
        KnowledgeGraphBuildEntity build = entity(42L, status);
        when(builds.selectForUpdate(42L)).thenReturn(build);
        when(builds.selectById(42L)).thenReturn(build);
        when(builds.selectOne(any())).thenReturn(build);
        return build;
    }

    @Test
    @DisplayName("启动抽取:置运行态、发放 run 标记与心跳,提交后直启管线")
    void startExtractionStartsPipeline() {
        KnowledgeGraphBuildEntity build = build(BuildStatus.TOC_READY);

        service().startExtraction(6L, 42L);

        assertThat(build.getStatus()).isEqualTo(BuildStatus.EXTRACTING);
        assertThat(build.getRunToken()).isNotNull();
        assertThat(build.getProgressHeartbeatAt()).isEqualTo(NOW_LOCAL);
        verify(pipeline).start(42L, BuildStatus.EXTRACTING, build.getRunToken());
    }

    @Test
    @DisplayName("中止:心跳停更超时(执行者已死)的直接判取消;活着的只写取消标记,由管线在安全边界收尾")
    void cancelDeadVersusAlive() {
        KnowledgeGraphBuildService service = service();
        KnowledgeGraphBuildEntity dead = build(BuildStatus.EXTRACTING); // 心跳停在一小时前,超过 progressTimeout
        service.cancel(6L, 42L);
        assertThat(dead.getStatus()).isEqualTo(BuildStatus.FAILED);
        assertThat(dead.getErrorMessage()).isEqualTo(BoundedParallel.CANCELLED);

        KnowledgeGraphBuildEntity alive = build(BuildStatus.EXTRACTING);
        alive.extracting("fresh-token", NOW_LOCAL); // 心跳刚跳过
        service.cancel(6L, 42L);
        assertThat(alive.getStatus()).isEqualTo(BuildStatus.EXTRACTING);
        assertThat(alive.isCancelRequested()).isTrue();

        build(BuildStatus.TOC_READY);
        assertThatThrownBy(() -> service.cancel(6L, 42L)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("运行态不能删除,须先中止")
    void deleteRejectsActive() {
        build(BuildStatus.PARSING);
        assertThatThrownBy(() -> service().delete(6L, 42L)).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("入库:图谱建好后删除构建行(含整本正文与预览),构建只是脚手架")
    void completePurgesTheBuild() {
        build(BuildStatus.EXTRACTED);
        when(editor.createFromPreview(anyLong(), any(), any())).thenReturn(
                new KnowledgeGraphService.GraphView(9L, 6L, "图谱", false, NOW, NOW));

        KnowledgeGraphService.GraphView graph = service().complete(6L, 42L, "图谱");

        assertThat(graph.id()).isEqualTo(9L);
        verify(purger).purgeBuilds(List.of(42L));
    }

    @Test
    @DisplayName("重新解析:运行中不允许;目录确认稿、小节与预览作废后回到解析态直启")
    @SuppressWarnings("unchecked")
    void retryParseRules() {
        KnowledgeGraphBuildService service = service();
        build(BuildStatus.EXTRACTING);
        assertThatThrownBy(() -> service.retryParse(6L, 42L)).isInstanceOf(ConflictException.class);

        KnowledgeGraphBuildEntity build = build(BuildStatus.TOC_READY);
        when(materials.createDownloadForManagement(6L, 3L)).thenReturn(new DownloadTicket("https://oss/x", NOW));
        service.retryParse(6L, 42L);

        assertThat(build.getStatus()).isEqualTo(BuildStatus.PARSING);
        assertThat(build.getRunToken()).isNotNull();
        verify(pipeline).start(42L, BuildStatus.PARSING, build.getRunToken());
        assertThat(build.getTocConfirmedJson()).isNull();
        assertThat(build.getPreviewJson()).isNull();
        verify(sections).delete(any(Wrapper.class));
    }

    @Test
    @DisplayName("改目录增量保留:切分签名未变的已完成小节带结果保留,其余回到待抽取")
    @SuppressWarnings("unchecked")
    void saveTocCarriesForwardUnchangedDoneSections() {
        build(BuildStatus.FAILED);
        List<TocEntry> oldEntries = List.of(
                new TocEntry("1", "甲节", 1, 1, 1), new TocEntry("2", "乙节", 1, 1, 1));
        List<SectionSlicer.Slice> oldSlices = SectionSlicer.slice(oldEntries, List.of("第一页"), 24000);
        List<BuildSectionEntity> oldRows = new ArrayList<>();
        for (int i = 0; i < oldSlices.size(); i++) {
            SectionSlicer.Slice slice = oldSlices.get(i);
            BuildSectionEntity row = new BuildSectionEntity(42L, i, slice.entryIndex(), slice.number(),
                    slice.title(), slice.path(), slice.startPage(), slice.endPage());
            row.done("{\"summary\":\"" + slice.title() + "\"}", "{\"knowledgePoints\":[]}");
            oldRows.add(row);
        }
        when(sections.selectList(any(Wrapper.class))).thenReturn(oldRows);
        when(sections.insert(any(BuildSectionEntity.class))).thenReturn(1);

        service().saveToc(6L, 42L, List.of(
                new KnowledgeGraphBuildService.TocEntryPayload("1", "甲节", 1, 1, 1),
                new KnowledgeGraphBuildService.TocEntryPayload("2", "乙节改", 1, 1, 1)));

        var captor = org.mockito.ArgumentCaptor.forClass(BuildSectionEntity.class);
        verify(sections, org.mockito.Mockito.times(2)).insert(captor.capture());
        List<BuildSectionEntity> inserted = captor.getAllValues();
        assertThat(inserted.get(0).getTitle()).isEqualTo("甲节");
        assertThat(inserted.get(0).getStatus()).isEqualTo(SectionStatus.DONE);
        assertThat(inserted.get(0).getResultJson()).isEqualTo("{\"knowledgePoints\":[]}");
        assertThat(inserted.get(1).getTitle()).isEqualTo("乙节改");
        assertThat(inserted.get(1).getStatus()).isEqualTo(SectionStatus.PENDING);
        assertThat(inserted.get(1).getResultJson()).isNull();
    }

    @Test
    @DisplayName("忽略失败小节:仅失败态放行,须无未抽取小节、有失败也有成功小节")
    @SuppressWarnings("unchecked")
    void mergeIgnoringFailedRules() {
        KnowledgeGraphBuildService service = service();

        build(BuildStatus.EXTRACTED);
        assertThatThrownBy(() -> service.mergeIgnoringFailed(6L, 42L))
                .isInstanceOf(ConflictException.class).hasMessage("只有抽取失败的构建可以忽略失败小节");

        build(BuildStatus.FAILED);
        BuildSectionEntity pending = new BuildSectionEntity(42L, 0, 0, "", "未抽节", "章", 1, 1);
        when(sections.selectList(any(Wrapper.class))).thenReturn(List.of(pending));
        assertThatThrownBy(() -> service.mergeIgnoringFailed(6L, 42L))
                .isInstanceOf(ConflictException.class).hasMessage("还有未抽取的小节，请先重试抽取");

        BuildSectionEntity done = new BuildSectionEntity(42L, 0, 0, "", "成功节", "章", 1, 1);
        done.done("{}", "{}");
        when(sections.selectList(any(Wrapper.class))).thenReturn(List.of(done));
        assertThatThrownBy(() -> service.mergeIgnoringFailed(6L, 42L))
                .isInstanceOf(ConflictException.class).hasMessage("没有失败的小节");

        BuildSectionEntity failed = new BuildSectionEntity(42L, 0, 0, "", "失败节", "章", 1, 1);
        failed.failed("原因");
        when(sections.selectList(any(Wrapper.class))).thenReturn(List.of(failed));
        assertThatThrownBy(() -> service.mergeIgnoringFailed(6L, 42L))
                .isInstanceOf(ConflictException.class).hasMessage("没有抽取成功的小节，无法合并");
    }

    @Test
    @DisplayName("忽略失败小节:失败片标 ignored(原因保留)后直启,管线只合并")
    @SuppressWarnings("unchecked")
    void mergeIgnoringFailedMarksAndEnqueues() {
        KnowledgeGraphBuildEntity build = build(BuildStatus.FAILED);
        BuildSectionEntity done = new BuildSectionEntity(42L, 0, 0, "", "成功节", "章", 1, 1);
        done.done("{}", "{\"knowledgePoints\":[]}");
        BuildSectionEntity failed = new BuildSectionEntity(42L, 1, 1, "", "失败节", "章", 2, 2);
        failed.failed("类型不合法");
        when(sections.selectList(any(Wrapper.class))).thenReturn(List.of(done, failed));
        when(sections.updateById(any(BuildSectionEntity.class))).thenReturn(1);

        service().mergeIgnoringFailed(6L, 42L);

        assertThat(failed.getStatus()).isEqualTo(SectionStatus.IGNORED);
        assertThat(failed.getErrorMessage()).isEqualTo("类型不合法");
        assertThat(GraphExtractionService.needsExtraction(failed)).isFalse();
        assertThat(build.getStatus()).isEqualTo(BuildStatus.EXTRACTING);
        verify(pipeline).start(42L, BuildStatus.EXTRACTING, build.getRunToken());
    }

    @Test
    @DisplayName("预览是类型化的入库文档")
    void previewIsTyped() throws Exception {
        KnowledgeGraphBuildEntity build = build(BuildStatus.TOC_READY);
        BuildPreview preview = new BuildPreview(List.of(new BuildPreview.PreviewNode("u0", null, 1, NodeKind.UNIT,
                null, "第一章", null, null, null, List.of(), null, null, null, null)), List.of(), List.of("提示"));
        build.extracted(json.writeValueAsString(preview), NOW_LOCAL);

        BuildPreview read = service().preview(6L, 42L);

        assertThat(read.nodes()).singleElement().satisfies(node -> {
            assertThat(node.key()).isEqualTo("u0");
            assertThat(node.kind()).isEqualTo(NodeKind.UNIT);
        });
        assertThat(read.warnings()).containsExactly("提示");
    }
}
