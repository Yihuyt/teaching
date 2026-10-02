package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.shared.run.RunFence;
import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.util.BoundedParallel;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.ai.document.MineruClient;
import cn.utcy.teaching.shared.sse.ProgressBus;
import cn.utcy.teaching.knowledgegraph.domain.BuildPreview;
import cn.utcy.teaching.knowledgegraph.domain.BuildStatus;
import cn.utcy.teaching.knowledgegraph.domain.TocEntry;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphBuildEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgeGraphBuildMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgegraphProperties;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 教材构建的直启执行管线:请求侧在事务里把行置为运行态并发放 run 标记,提交后交给本类在虚拟线程上
 * 执行解析(MinerU → 目录识别 → 目录草稿落库)或抽取(未完成小节 → 合并修复 → 预览落库)。
 * 写围栏:每次落库(含进展心跳)都要求行上的 run_token 仍是本次发放的——判滞巡检判败或新一次启动
 * 都会换掉标记,迟到的旧线程写什么都不生效、结果作废。取消与围栏经节流的进展心跳
 * ({@link RunFence.Heartbeat})观察,在下一个安全边界停下。进程死亡不在此处理:
 * 心跳停更后由 {@link StaleGraphBuildCleaner} 判失败,教师从断点重试。
 */
@Service
public class KnowledgeGraphBuildPipeline {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeGraphBuildPipeline.class);

    private final KnowledgeGraphBuildMapper builds;
    private final BuildSectionMapper sections;
    private final TocRecognitionService tocRecognition;
    private final GraphExtractionService extraction;
    private final MineruClient mineru;
    private final CourseMaterialApplicationService materials;
    private final CourseAiKeys aiKeys;
    private final KnowledgegraphProperties properties;
    private final BuildDocuments documents;
    private final ProgressBus bus;
    private final TaskExecutor executor;
    private final TransactionOperations transactions;
    private final Clock clock;

    public KnowledgeGraphBuildPipeline(KnowledgeGraphBuildMapper builds,
                                       BuildSectionMapper sections,
                                       TocRecognitionService tocRecognition,
                                       GraphExtractionService extraction,
                                       MineruClient mineru,
                                       CourseMaterialApplicationService materials,
                                       CourseAiKeys aiKeys,
                                       KnowledgegraphProperties properties,
                                       BuildDocuments documents,
                                       ProgressBus bus,
                                       @Qualifier("kgTaskExecutor") TaskExecutor executor,
                                       TransactionOperations transactions,
                                       Clock clock) {
        this.builds = builds;
        this.sections = sections;
        this.tocRecognition = tocRecognition;
        this.extraction = extraction;
        this.mineru = mineru;
        this.materials = materials;
        this.aiKeys = aiKeys;
        this.properties = properties;
        this.documents = documents;
        this.bus = bus;
        this.executor = executor;
        this.transactions = transactions;
        this.clock = clock;
    }

    public static String buildChannel(long buildId) {
        return "kg-build-" + buildId;
    }

    /** 启动一次运行:调用方已在**已提交**的事务里把行置为运行态并写入 runToken */
    public void start(long buildId, BuildStatus stage, String runToken) {
        executor.execute(() -> new Execution(buildId, stage, runToken).run());
    }

    private final class Execution {

        private final long buildId;
        private final BuildStatus stage;
        private final String token;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final RunFence.Heartbeat heartbeat;

        Execution(long buildId, BuildStatus stage, String token) {
            this.buildId = buildId;
            this.stage = stage;
            this.token = token;
            this.heartbeat = new RunFence.Heartbeat(clock, this::writeBeat);
        }

        void run() {
            bus.open(buildChannel(buildId));
            BooleanSupplier stop = () -> {
                heartbeat.beat();
                return cancelled.get() || heartbeat.lost();
            };
            String error = null;
            try {
                KnowledgeGraphBuildEntity build = builds.selectById(buildId);
                if (stage == BuildStatus.PARSING) {
                    parse(build, stop);
                } else {
                    extract(build, stop);
                }
            } catch (RuntimeException exception) {
                error = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
                if (!cancelled.get() && !heartbeat.lost()) {
                    log.warn("构建 {} 失败:{}", buildId, error);
                }
            }
            if (heartbeat.lost()) {
                bus.terminate(buildChannel(buildId));
                return;
            }
            if (error != null) {
                String message = cancelled.get() ? BoundedParallel.CANCELLED : error;
                writeIfToken(locked -> locked.failed(message, now()));
                bus.publish(buildChannel(buildId), Map.of("type", "error", "message", message));
            }
            bus.terminate(buildChannel(buildId));
        }

        private boolean writeBeat() {
            if (builds.beat(buildId, token, now()) == 0) {
                return false;
            }
            if (Boolean.TRUE.equals(builds.cancelRequested(buildId))) {
                cancelled.set(true);
            }
            return true;
        }

        private void parse(KnowledgeGraphBuildEntity build, BooleanSupplier stop) {
            if (build.getMaterialId() == null) {
                throw new IllegalStateException("源资料已删除，无法解析");
            }
            String llmKey = aiKeys.llmKeyForCourse(build.getCourseId());
            String mineruToken = aiKeys.mineruTokenForCourse(build.getCourseId());
            // 下载地址在真正开始时签发(预签名短时有效)
            String fileUrl = materials.createDownloadTrusted(build.getCourseId(), build.getMaterialId()).url();
            Consumer<String> parseProgress = message ->
                    progress(Map.of("type", "parse_progress", "message", message));

            stageEvent("parsing", "正在通过 MinerU 解析教材…");
            List<String> pages = mineru.parseToPages(mineruToken, fileUrl, properties.maxBookPages(),
                    mineruLedger(build), parseProgress, stop);
            long totalChars = pages.stream().mapToLong(String::length).sum();
            if (totalChars > properties.maxBookChars()) {
                throw new BadRequestException("教材解析文本过大（" + totalChars + " 字符，上限 "
                        + properties.maxBookChars() + "），请拆分教材后分别构建");
            }
            stageEvent("toc", "正在识别教材目录…");
            TocRecognitionService.TocDraft draft = tocRecognition.recognize(llmKey, pages, parseProgress);
            BoundedParallel.requireNotCancelled(stop);

            String pagesJson = documents.writeJson(pages);
            String draftJson = documents.writeDraft(draft);
            if (writeIfToken(locked -> locked.tocReady(pagesJson, draftJson, now()))) {
                bus.publish(buildChannel(buildId), Map.of("type", "toc_ready", "degraded", draft.degraded()));
            }
        }

        /** MinerU 任务登记落在构建行上:重试续跑时续接云端任务,不重复提交整本书 */
        private MineruClient.TaskLedger mineruLedger(KnowledgeGraphBuildEntity build) {
            Map<String, String> tasks = documents.readMineruTasks(build);
            // 显式锁而非 synchronized:并行分段跑在虚拟线程上,锁内有落库 I/O,不能钉住载体线程
            ReentrantLock lock = new ReentrantLock();
            return new MineruClient.TaskLedger() {
                @Override
                public String taskId(String segment) {
                    return tasks.get(segment);
                }

                @Override
                public void record(String segment, String taskId) {
                    lock.lock();
                    try {
                        tasks.put(segment, taskId);
                        String json = documents.writeJson(tasks);
                        writeIfToken(locked -> locked.rememberMineruTasks(json, now()));
                    } finally {
                        lock.unlock();
                    }
                }
            };
        }

        private void extract(KnowledgeGraphBuildEntity build, BooleanSupplier stop) {
            String llmKey = aiKeys.llmKeyForCourse(build.getCourseId());
            String bookTitle = BuildDocuments.bookTitle(build);
            List<TocEntry> entries = documents.readConfirmed(build);
            List<BuildSectionEntity> rows = sections.selectList(new LambdaQueryWrapper<BuildSectionEntity>()
                    .eq(BuildSectionEntity::getBuildId, buildId)
                    .orderByAsc(BuildSectionEntity::getSectionIndex));

            if (rows.stream().anyMatch(GraphExtractionService::needsExtraction)) {
                stageEvent("extracting", "正在抽取各小节的知识点（并行 "
                        + properties.sectionConcurrency() + " 路）…");
                List<String> failures = extraction.extractSections(llmKey, bookTitle, rows,
                        documents.sliceTexts(build, entries),
                        (done, total, title) -> progress(Map.of("type", "progress",
                                "current", done, "total", total, "sectionTitle", title)),
                        stop);
                if (!failures.isEmpty()) {
                    throw new IllegalStateException(failures.size() + " 个小节抽取失败，可重试（只跑未完成的小节），"
                            + "或忽略失败小节直接合并：" + String.join("；", failures));
                }
            }

            stageEvent("merging", "正在合并与修复…");
            BuildPreview preview = extraction.finalizeGraph(llmKey, bookTitle, entries, rows,
                    label -> stageEvent("merging", label), stop);
            String previewJson = documents.writeJson(preview);
            if (writeIfToken(locked -> locked.extracted(previewJson, now()))) {
                bus.publish(buildChannel(buildId), Map.of("type", "extracted",
                        "nodeCount", preview.nodes().size(),
                        "edgeCount", preview.edges().size(),
                        "warnings", preview.warnings()));
            }
        }

        private boolean writeIfToken(Consumer<KnowledgeGraphBuildEntity> change) {
            Boolean written = transactions.execute(status -> {
                KnowledgeGraphBuildEntity locked = builds.selectForUpdate(buildId);
                if (locked == null || !token.equals(locked.getRunToken())) {
                    return false;
                }
                change.accept(locked);
                return builds.updateById(locked) == 1;
            });
            if (!Boolean.TRUE.equals(written)) {
                heartbeat.markLost();
                return false;
            }
            return true;
        }

        private void stageEvent(String stage, String label) {
            progress(Map.of("type", "stage", "stage", stage, "label", label));
        }

        private void progress(Map<String, Object> event) {
            heartbeat.beat();
            bus.publish(buildChannel(buildId), event);
        }

        private LocalDateTime now() {
            return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        }
    }
}
