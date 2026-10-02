package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.shared.run.RunFence;
import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.shared.util.BoundedParallel;
import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.sse.ProgressBus;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.GraphView;
import cn.utcy.teaching.knowledgegraph.domain.BuildPreview;
import cn.utcy.teaching.knowledgegraph.domain.BuildStatus;
import cn.utcy.teaching.knowledgegraph.domain.SectionSlicer;
import cn.utcy.teaching.knowledgegraph.domain.SectionStatus;
import cn.utcy.teaching.knowledgegraph.domain.TocEntry;
import cn.utcy.teaching.knowledgegraph.domain.TocOutline;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphBuildEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphBuildMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphRowPurger;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgegraphProperties;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService.MaterialView;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 教材构建的请求侧:
 * parsing → toc_ready →(教师确认 + 启动)extracting → extracted →(命名入库,构建行随之删除);
 * 运行态可 → failed(带中文原因,可重试,断点数据保留)。这里在事务内完成状态迁移(锁构建行、发放 run 标记),
 * 提交后交给 {@link KnowledgeGraphBuildPipeline} 直启执行;SSE 经 ProgressBus attach(断线重连不杀任务)。
 */
@Service
public class KnowledgeGraphBuildService {

    private final KnowledgeGraphBuildMapper builds;
    private final BuildSectionMapper sections;
    private final KnowledgeGraphEditor editor;
    private final CourseAiKeys aiKeys;
    private final CourseMaterialApplicationService materials;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final KnowledgegraphProperties properties;
    private final ObjectMapper objectMapper;
    private final BuildDocuments documents;
    private final ProgressBus bus;
    private final KnowledgeGraphRowPurger purger;
    private final KnowledgeGraphBuildPipeline pipeline;
    private final TransactionOperations transactions;
    private final Clock clock;

    public KnowledgeGraphBuildService(KnowledgeGraphBuildMapper builds,
                                      BuildSectionMapper sections,
                                      KnowledgeGraphEditor editor,
                                      CourseAiKeys aiKeys,
                                      CourseMaterialApplicationService materials,
                                      CourseAccess courseAccess,
                                      CurrentActor currentActor,
                                      KnowledgegraphProperties properties,
                                      ObjectMapper objectMapper,
                                      BuildDocuments documents,
                                      ProgressBus bus,
                                      KnowledgeGraphRowPurger purger,
                                      KnowledgeGraphBuildPipeline pipeline,
                                      TransactionOperations transactions,
                                      Clock clock) {
        this.builds = builds;
        this.sections = sections;
        this.editor = editor;
        this.aiKeys = aiKeys;
        this.materials = materials;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.documents = documents;
        this.bus = bus;
        this.purger = purger;
        this.pipeline = pipeline;
        this.transactions = transactions;
        this.clock = clock;
    }

    // ---- 创建与解析 ------------------------------------------------------------

    public BuildView create(long courseId, long materialId) {
        Actor actor = currentActor.require();
        courseAccess.requireManagementAccess(courseId, actor);
        MaterialView material = requirePdfMaterial(courseId, materialId);
        // 密钥缺失在提交时就报,而不是排到队头才失败
        aiKeys.llmKeyForCourse(courseId);
        aiKeys.mineruTokenForCourse(courseId);
        // 只为校验文件已上传完成(未就绪 409);解析用的下载地址由运行器在真正开始时签发
        materials.createDownloadForManagement(courseId, materialId);
        String runToken = RunFence.newToken();
        KnowledgeGraphBuildEntity build = transactions.execute(status -> {
            KnowledgeGraphBuildEntity created = new KnowledgeGraphBuildEntity(
                    courseId, materialId, material.name(), actor.userId(), runToken, now());
            requireMutation(builds.insert(created), "构建任务创建未生效");
            return created;
        });
        pipeline.start(build.getId(), BuildStatus.PARSING, runToken);
        return view(build);
    }

    public BuildView retryParse(long courseId, long buildId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        aiKeys.llmKeyForCourse(courseId);
        aiKeys.mineruTokenForCourse(courseId);
        String runToken = RunFence.newToken();
        KnowledgeGraphBuildEntity build = transactions.execute(status -> {
            KnowledgeGraphBuildEntity locked = requireForUpdate(courseId, buildId);
            if (locked.getStatus() != BuildStatus.FAILED
                    && locked.getStatus() != BuildStatus.TOC_READY
                    && locked.getStatus() != BuildStatus.EXTRACTED) {
                throw new ConflictException("当前状态不能重新解析");
            }
            if (locked.getMaterialId() == null) {
                throw new ConflictException("源资料已删除，无法重新解析");
            }
            materials.createDownloadForManagement(courseId, locked.getMaterialId());
            locked.reparse(runToken, now());
            requireMutation(builds.updateById(locked), "构建状态已变化，重试未生效");
            sections.delete(new LambdaQueryWrapper<BuildSectionEntity>().eq(BuildSectionEntity::getBuildId, buildId));
            return locked;
        });
        pipeline.start(buildId, BuildStatus.PARSING, runToken);
        return view(build);
    }

    // ---- 目录确认与抽取 --------------------------------------------------------

    @Transactional
    public void saveToc(long courseId, long buildId, List<TocEntryPayload> payload) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraphBuildEntity build = requireForUpdate(courseId, buildId);
        if (build.getStatus() != BuildStatus.TOC_READY
                && build.getStatus() != BuildStatus.EXTRACTED
                && build.getStatus() != BuildStatus.FAILED) {
            throw new ConflictException("当前状态不能修改目录（解析或抽取正在进行中）");
        }
        if (build.getPageMarkdownJson() == null) {
            throw new ConflictException("教材尚未完成解析，不能确认目录");
        }
        List<String> pages = documents.readPages(build);
        List<TocEntry> entries = payload.stream()
                .map(item -> new TocEntry(
                        item.number() == null ? "" : item.number().trim(),
                        item.title().trim(), item.level(), item.page(), item.endPage()))
                .toList();
        TocOutline.validate(entries, pages.size());

        build.confirmToc(documents.writeConfirmed(entries), now());
        if (build.getStatus() != BuildStatus.TOC_READY) {
            build.backToTocReady(now());
        }
        requireMutation(builds.updateById(build), "构建状态已变化，目录确认未生效");

        // 重切抽取子片,增量保留:解析后的分页文本不可变,片的抽取输入完全由签名
        // (编号+标题+路径+起止页)决定——签名未变的已完成片直接带结果保留,只有真正
        // 受影响的片(被删条目的邻居、改名条目及其子孙)回到待抽取
        List<SectionSlicer.Slice> slices = SectionSlicer.slice(entries, pages, properties.maxSectionChars());
        if (slices.isEmpty()) {
            throw new BadRequestException("确认的目录切分不出任何抽取小节，请检查目录页码");
        }
        Map<SliceSignature, BuildSectionEntity> doneBySignature = new HashMap<>();
        for (BuildSectionEntity row : sections.selectList(
                new LambdaQueryWrapper<BuildSectionEntity>().eq(BuildSectionEntity::getBuildId, buildId))) {
            if (row.getStatus() == SectionStatus.DONE) {
                doneBySignature.put(new SliceSignature(row.getNumber(), row.getTitle(), row.getPath(),
                        row.getStartPage(), row.getEndPage()), row);
            }
        }
        sections.delete(new LambdaQueryWrapper<BuildSectionEntity>().eq(BuildSectionEntity::getBuildId, buildId));
        for (int i = 0; i < slices.size(); i++) {
            SectionSlicer.Slice slice = slices.get(i);
            BuildSectionEntity row = new BuildSectionEntity(
                    buildId, i, slice.entryIndex(), slice.number(), slice.title(),
                    slice.path(), slice.startPage(), slice.endPage());
            BuildSectionEntity carried = doneBySignature.get(new SliceSignature(slice.number(), slice.title(),
                    slice.path(), slice.startPage(), slice.endPage()));
            if (carried != null) {
                row.done(carried.getSummaryJson(), carried.getResultJson());
            }
            requireMutation(sections.insert(row), "抽取小节写入未生效");
        }
    }

    /** 抽取子片的切分签名:分页文本不可变,签名相同 ⇒ 抽取输入相同 */
    private record SliceSignature(String number, String title, String path, int startPage, int endPage) {
    }

    public BuildView startExtraction(long courseId, long buildId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        aiKeys.llmKeyForCourse(courseId);
        String runToken = RunFence.newToken();
        KnowledgeGraphBuildEntity build = transactions.execute(status -> {
            KnowledgeGraphBuildEntity locked = requireForUpdate(courseId, buildId);
            if (locked.getStatus() != BuildStatus.TOC_READY
                    && locked.getStatus() != BuildStatus.EXTRACTED
                    && locked.getStatus() != BuildStatus.FAILED) {
                throw new ConflictException("当前状态不能启动抽取");
            }
            if (locked.getTocConfirmedJson() == null) {
                throw new ConflictException("请先确认目录再启动抽取");
            }
            locked.extracting(runToken, now());
            requireMutation(builds.updateById(locked), "构建状态已变化，启动未生效");
            return locked;
        });
        pipeline.start(buildId, BuildStatus.EXTRACTING, runToken);
        return view(build);
    }

    /** 忽略失败小节直接合并:失败片标 ignored(不进图,目录条目保留为空章节),管线只用成功片走合并 */
    public BuildView mergeIgnoringFailed(long courseId, long buildId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        aiKeys.llmKeyForCourse(courseId);
        String runToken = RunFence.newToken();
        KnowledgeGraphBuildEntity build = transactions.execute(status -> {
            KnowledgeGraphBuildEntity locked = requireForUpdate(courseId, buildId);
            if (locked.getStatus() != BuildStatus.FAILED) {
                throw new ConflictException("只有抽取失败的构建可以忽略失败小节");
            }
            List<BuildSectionEntity> rows = sections.selectList(
                    new LambdaQueryWrapper<BuildSectionEntity>()
                            .eq(BuildSectionEntity::getBuildId, buildId)
                            .orderByAsc(BuildSectionEntity::getSectionIndex));
            if (rows.stream().anyMatch(row -> row.getStatus() == SectionStatus.PENDING
                    || row.getStatus() == SectionStatus.RUNNING)) {
                throw new ConflictException("还有未抽取的小节，请先重试抽取");
            }
            if (rows.stream().noneMatch(row -> row.getStatus() == SectionStatus.FAILED)) {
                throw new ConflictException("没有失败的小节");
            }
            if (rows.stream().noneMatch(row -> row.getStatus() == SectionStatus.DONE)) {
                throw new ConflictException("没有抽取成功的小节，无法合并");
            }
            for (BuildSectionEntity row : rows) {
                if (row.getStatus() == SectionStatus.FAILED) {
                    row.ignored();
                    requireMutation(sections.updateById(row), "小节状态更新未生效");
                }
            }
            locked.extracting(runToken, now());
            requireMutation(builds.updateById(locked), "构建状态已变化，启动未生效");
            return locked;
        });
        pipeline.start(buildId, BuildStatus.EXTRACTING, runToken);
        return view(build);
    }

    // ---- 预览 / 入库 / 取消 / 删除 --------------------------------------------

    public BuildPreview preview(long courseId, long buildId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraphBuildEntity build = require(courseId, buildId);
        if (build.getStatus() != BuildStatus.EXTRACTED) {
            throw new ConflictException("抽取尚未完成，暂无预览");
        }
        return documents.readPreview(build);
    }

    /** 入库 = 新建图谱 + 一个事务写入全部节点与关系;图谱是产物,构建只是脚手架,入库即删构建行(含整本正文与预览) */
    @Transactional
    public GraphView complete(long courseId, long buildId, String name) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraphBuildEntity build = requireForUpdate(courseId, buildId);
        if (build.getStatus() != BuildStatus.EXTRACTED) {
            throw new ConflictException("只有抽取完成的构建可以入库");
        }
        GraphView graph = editor.createFromPreview(courseId, name, documents.readPreview(build));
        purger.purgeBuilds(List.of(buildId));
        return graph;
    }

    /** 中止:执行者活着(心跳未停更超时)写取消标记,管线在下一个安全边界停下;执行者已死没人消费标记,直接判取消 */
    @Transactional
    public void cancel(long courseId, long buildId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraphBuildEntity build = requireForUpdate(courseId, buildId);
        if (!build.isActive()) {
            throw new ConflictException("当前状态没有正在运行的任务");
        }
        LocalDateTime staleBefore = now().minus(properties.progressTimeout());
        if (build.getProgressHeartbeatAt() == null || build.getProgressHeartbeatAt().isBefore(staleBefore)) {
            build.failed(BoundedParallel.CANCELLED, now());
        } else {
            build.requestCancel(now());
        }
        requireMutation(builds.updateById(build), "构建状态已变化，中止未生效");
    }

    @Transactional
    public void delete(long courseId, long buildId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraphBuildEntity build = requireForUpdate(courseId, buildId);
        if (build.isActive()) {
            throw new ConflictException("任务正在运行中，请先中止再删除");
        }
        purger.purgeBuilds(List.of(buildId));
    }

    // ---- 查询与 SSE ------------------------------------------------------------

    public List<BuildView> list(long courseId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        return builds.selectList(new LambdaQueryWrapper<KnowledgeGraphBuildEntity>()
                        .eq(KnowledgeGraphBuildEntity::getCourseId, courseId)
                        .orderByDesc(KnowledgeGraphBuildEntity::getUpdatedAt))
                .stream().map(this::view).toList();
    }

    public BuildDetailView get(long courseId, long buildId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraphBuildEntity build = require(courseId, buildId);
        JsonNode draftDocument = build.getTocDraftJson() == null ? null
                : documents.readJson(build.getTocDraftJson(), "目录草稿");
        List<TocEntryView> draft = draftDocument == null ? List.of() : entryViews(draftDocument.path("entries"));
        List<TocEntryView> confirmed = build.getTocConfirmedJson() == null ? List.of()
                : entryViews(documents.readJson(build.getTocConfirmedJson(), "目录确认稿").path("entries"));
        List<String> notes = draftDocument == null ? List.of() : textList(draftDocument.path("notes"));
        boolean degraded = draftDocument != null && draftDocument.path("degraded").asBoolean(false);
        int pageCount = build.getPageMarkdownJson() == null ? 0 : documents.readPages(build).size();
        List<SectionView> sectionViews = sections.selectList(
                        new LambdaQueryWrapper<BuildSectionEntity>()
                                .eq(BuildSectionEntity::getBuildId, buildId)
                                .orderByAsc(BuildSectionEntity::getSectionIndex))
                .stream()
                .map(row -> new SectionView(row.getSectionIndex(), row.getTitle(), row.getPath(),
                        row.getStartPage(), row.getEndPage(), row.getStatus(),
                        row.getErrorMessage()))
                .toList();
        return new BuildDetailView(view(build), degraded, notes, pageCount, draft, confirmed, sectionViews);
    }

    public PagePreviewView pagePreview(long courseId, long buildId, int page) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraphBuildEntity build = require(courseId, buildId);
        if (build.getPageMarkdownJson() == null) {
            throw new ConflictException("教材尚未完成解析");
        }
        List<String> pages = documents.readPages(build);
        if (page < 1 || page > pages.size()) {
            throw new BadRequestException("页码必须在 1~" + pages.size() + " 之间");
        }
        String text = pages.get(page - 1);
        return new PagePreviewView(page, Text.truncate(text, 1200));
    }

    public SseEmitter attachEvents(long courseId, long buildId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeGraphBuildEntity build = require(courseId, buildId);
        SseEmitter live = bus.attach(KnowledgeGraphBuildPipeline.buildChannel(buildId));
        if (live != null) {
            return live;
        }
        SseEmitter emitter = new SseEmitter(0L);
        try {
            Map<String, Object> event = build.getStatus() == BuildStatus.FAILED
                    ? Map.of("type", "error", "message",
                    build.getErrorMessage() == null ? "构建失败" : build.getErrorMessage())
                    : Map.of("type", "status", "status", build.getStatus().value());
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event)));
            emitter.complete();
        } catch (IOException | IllegalStateException exception) {
            // 客户端已断开,无需处理
        }
        return emitter;
    }

    // ---- 视图 ------------------------------------------------------------------------

    public record TocEntryPayload(
            @Schema(nullable = true) String number,
            String title,
            int level,
            int page,
            int endPage
    ) {
    }

    public record TocEntryView(String number, String title, int level, int page, int endPage) {
    }

    public record BuildView(
            long id,
            long courseId,
            @Schema(nullable = true) Long materialId,
            String materialName,
            BuildStatus status,
            @Schema(nullable = true) String errorMessage,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record SectionView(int sectionIndex, String title, String path, int startPage,
                              int endPage, SectionStatus status,
                              @Schema(nullable = true) String errorMessage) {
    }

    public record BuildDetailView(BuildView build, boolean degraded, List<String> notes,
                                  int pageCount, List<TocEntryView> tocDraft,
                                  List<TocEntryView> tocConfirmed,
                                  List<SectionView> sections) {
    }

    public record PagePreviewView(int page, String text) {
    }

    private BuildView view(KnowledgeGraphBuildEntity build) {
        return new BuildView(build.getId(), build.getCourseId(), build.getMaterialId(),
                build.getMaterialName(), build.getStatus(), build.getErrorMessage(),
                build.getCreatedAt().toInstant(ZoneOffset.UTC),
                build.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    private List<TocEntryView> entryViews(JsonNode entries) {
        List<TocEntryView> views = new ArrayList<>();
        for (JsonNode entry : entries) {
            views.add(new TocEntryView(entry.path("number").asText(""),
                    entry.path("title").asText(""),
                    entry.path("level").asInt(1),
                    entry.path("page").asInt(0),
                    entry.path("endPage").asInt(0)));
        }
        return views;
    }

    private MaterialView requirePdfMaterial(long courseId, long materialId) {
        MaterialView material = materials.getTrusted(courseId, materialId);
        if (!material.name().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new BadRequestException("教材构建只支持 PDF 资料");
        }
        return material;
    }

    private KnowledgeGraphBuildEntity require(long courseId, long buildId) {
        KnowledgeGraphBuildEntity build = builds.selectOne(
                new LambdaQueryWrapper<KnowledgeGraphBuildEntity>()
                        .eq(KnowledgeGraphBuildEntity::getCourseId, courseId)
                        .eq(KnowledgeGraphBuildEntity::getId, buildId));
        if (build == null) {
            throw new NotFoundException("课程中不存在该构建任务");
        }
        return build;
    }

    private KnowledgeGraphBuildEntity requireForUpdate(long courseId, long buildId) {
        KnowledgeGraphBuildEntity build = builds.selectForUpdate(buildId);
        if (build == null || !build.getCourseId().equals(courseId)) {
            throw new NotFoundException("课程中不存在该构建任务");
        }
        return build;
    }

    private static List<String> textList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void requireMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new ConflictException(message);
        }
    }
}
