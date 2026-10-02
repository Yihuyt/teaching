package cn.utcy.teaching.knowledgebase.application;

import java.time.Clock;
import cn.utcy.teaching.shared.run.RunFence;
import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.ai.llm.LlmCalls;
import cn.utcy.teaching.ai.document.DocumentParser;
import cn.utcy.teaching.knowledgebase.infrastructure.EmbeddingSignature;
import cn.utcy.teaching.shared.sse.ProgressBus;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseService.KbDocumentView;
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
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService.MaterialView;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 文档入库/重建 pipeline:解析 → 分块(MySQL 真源)→ 分批向量化 → 入 ES。
 * 任务与连接解耦:请求线程完成鉴权 / 校验 / 密钥与预签名地址解析(4xx 不进后台)后启动后台任务
 * (虚拟线程,不设平台侧并发上限——外部模型服务的限流即自然背压),进度经 {@link ProgressBus}
 * 广播——SSE attach 断线重连不影响任务,中止是显式接口。在途文档的写围栏与进展心跳见 {@link RunFence},
 * 停更(服务重启 / 挂死)由判滞巡检判失败,教师可重试。
 * 同一知识库的重建与入库互斥;重建永远写新的物理索引,成功才切 active_index_name 指针,在用索引从不被就地删改。
 */
@Service
public class DocumentIngestService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestService.class);

    /** _bulk 每批条数:控制单请求体积(约 2MB 量级) */
    private static final int BULK_BATCH = 100;
    static final String RESULT_DISCARDED = "入库结果作废：任务已被判中断或被新的重试接手";

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseMapper knowledgeBases;
    private final KbDocumentMapper documents;
    private final KbChunkMapper chunks;
    private final EsClient es;
    private final DocumentParser parser;
    private final Chunker chunker;
    private final LlmCalls llm;
    private final CourseAiKeys aiKeys;
    private final CourseMaterialApplicationService materials;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final TransactionTemplate transactions;
    private final KnowledgebaseProperties properties;
    private final ObjectMapper objectMapper;
    private final ProgressBus bus;
    private final TaskExecutor ingestExecutor;
    private final Clock clock;
    private final Map<String, RunningTask> running = new ConcurrentHashMap<>();

    private static final class RunningTask {
        final long kbId;
        final AtomicBoolean cancelled = new AtomicBoolean();

        RunningTask(long kbId) {
            this.kbId = kbId;
        }
    }

    public DocumentIngestService(KnowledgeBaseService knowledgeBaseService,
                                 KnowledgeBaseMapper knowledgeBases,
                                 KbDocumentMapper documents, KbChunkMapper chunks,
                                 EsClient es, DocumentParser parser, Chunker chunker,
                                 LlmCalls llm, CourseAiKeys aiKeys,
                                 CourseMaterialApplicationService materials,
                                 CourseAccess courseAccess, CurrentActor currentActor,
                                 KnowledgebaseProperties properties, ObjectMapper objectMapper, ProgressBus bus,
                                 @Qualifier("kbIngestExecutor") TaskExecutor ingestExecutor,
                                 PlatformTransactionManager transactionManager, Clock clock) {
        this.transactions = new TransactionTemplate(transactionManager);
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.chunks = chunks;
        this.es = es;
        this.parser = parser;
        this.chunker = chunker;
        this.llm = llm;
        this.aiKeys = aiKeys;
        this.materials = materials;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.bus = bus;
        this.ingestExecutor = ingestExecutor;
        this.clock = clock;
    }

    static String documentChannel(long documentId) {
        return "kb-document-" + documentId;
    }

    static String rebuildChannel(long kbId) {
        return "kb-rebuild-" + kbId;
    }

    // ---- 启动(请求线程,返回即任务在后台跑) ------------------------------------

    public KbDocumentView ingest(long courseId, long kbId, long materialId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeBaseEntity kb = knowledgeBaseService.require(courseId, kbId);
        requireSignatureCurrent(kb);
        requireNoRebuildRunning(kb.getId());

        MaterialView material = requireIndexableMaterial(courseId, materialId);
        if (documents.exists(new LambdaQueryWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getKnowledgeBaseId, kb.getId())
                .eq(KbDocumentEntity::getMaterialId, materialId))) {
            throw new ConflictException("该资料已在知识库中,如需更新请先删除原文档");
        }

        // 密钥与预签名地址在请求线程解析:未配置立刻 400,不进后台
        String llmKey = aiKeys.llmKeyForCourse(courseId);
        String mineruToken = isPdf(material.name()) ? aiKeys.mineruTokenForCourse(courseId) : null;
        String fileUrl = materials.createDownloadForManagement(courseId, materialId).url();

        KbDocumentEntity document = new KbDocumentEntity(kb.getId(), materialId,
                material.name(), now());
        requireMutation(documents.insert(document), "文档创建未生效");

        startDocumentTask(kb, document, llmKey, mineruToken, fileUrl);
        return KnowledgeBaseService.documentView(document);
    }

    public KbDocumentView retry(long courseId, long kbId, long documentId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeBaseEntity kb = knowledgeBaseService.require(courseId, kbId);
        requireSignatureCurrent(kb);
        requireNoRebuildRunning(kb.getId());
        KbDocumentEntity document = knowledgeBaseService.requireDocument(kb.getId(), documentId);
        if (document.getState() != DocumentState.ERROR) {
            throw new ConflictException("只有入库失败的文档可以重试");
        }
        if (document.getMaterialId() == null) {
            throw new ConflictException("源资料已删除,无法重新解析");
        }

        String llmKey = aiKeys.llmKeyForCourse(courseId);
        String mineruToken = isPdf(document.getName())
                ? aiKeys.mineruTokenForCourse(courseId) : null;
        String fileUrl = materials.createDownloadForManagement(courseId, document.getMaterialId()).url();
        startDocumentTask(kb, document, llmKey, mineruToken, fileUrl);
        return KnowledgeBaseService.documentView(document);
    }

    public record RebuildStarted(int documentCount) {
    }

    /**
     * 重建索引:从 MySQL chunk 重新向量化(不重新解析,源资料已删的文档同样可重建),
     * 写**新的物理索引**,全部成功才切 active_index_name / active_signature 指针,
     * 之后清理旧物理索引(失败只记日志);中途失败删新索引,指针不动——在用索引全程可查。
     */
    public RebuildStarted rebuild(long courseId, long kbId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeBaseEntity kb = knowledgeBaseService.require(courseId, kbId);
        String llmKey = aiKeys.llmKeyForCourse(courseId);
        requireNoIngestRunning(kb.getId());
        List<KbDocumentEntity> readyDocuments = documents.selectList(
                new LambdaQueryWrapper<KbDocumentEntity>()
                        .eq(KbDocumentEntity::getKnowledgeBaseId, kb.getId())
                        .eq(KbDocumentEntity::getState, DocumentState.READY)
                        .orderByAsc(KbDocumentEntity::getId));
        if (readyDocuments.isEmpty()) {
            throw new ConflictException("知识库中没有已就绪的文档,无需重建索引");
        }
        String newSignature = knowledgeBaseService.currentSignature();
        String newIndex = EmbeddingSignature.newIndexName(kb.getId(), newSignature);
        submit(rebuildChannel(kb.getId()), new RunningTask(kb.getId()),
                task -> runRebuild(task, kb, readyDocuments, llmKey, newSignature, newIndex));
        return new RebuildStarted(readyDocuments.size());
    }

    // ---- 进度与中止 ------------------------------------------------------------

    public SseEmitter documentEvents(long courseId, long kbId, long documentId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeBaseEntity kb = knowledgeBaseService.require(courseId, kbId);
        KbDocumentEntity document = knowledgeBaseService.requireDocument(kb.getId(), documentId);
        SseEmitter live = bus.attach(documentChannel(documentId));
        if (live != null) {
            return live;
        }
        return settledEmitter(Map.of("type", "status", "state", document.getState().name().toLowerCase(),
                "errorMessage", document.getErrorMessage() == null ? "" : document.getErrorMessage()));
    }

    public SseEmitter rebuildEvents(long courseId, long kbId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeBaseEntity kb = knowledgeBaseService.require(courseId, kbId);
        SseEmitter live = bus.attach(rebuildChannel(kb.getId()));
        if (live != null) {
            return live;
        }
        return settledEmitter(Map.of("type", "status", "active", false));
    }

    public void cancelDocument(long courseId, long kbId, long documentId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        knowledgeBaseService.requireDocument(
                knowledgeBaseService.require(courseId, kbId).getId(), documentId);
        cancel(documentChannel(documentId));
    }

    public void cancelRebuild(long courseId, long kbId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        knowledgeBaseService.require(courseId, kbId);
        cancel(rebuildChannel(kbId));
    }

    private void cancel(String channel) {
        RunningTask task = running.get(channel);
        if (task != null) {
            task.cancelled.set(true);
        }
    }

    // ---- 任务执行 --------------------------------------------------------------

    private void startDocumentTask(KnowledgeBaseEntity kb, KbDocumentEntity document,
                                   String llmKey, String mineruToken, String fileUrl) {
        submit(documentChannel(document.getId()), new RunningTask(kb.getId()),
                task -> runDocumentPipeline(task, kb.getId(), document, llmKey, mineruToken, fileUrl));
    }

    private interface IngestTask {
        void run(RunningTask task) throws Exception;
    }

    private void submit(String channel, RunningTask task, IngestTask body) {
        if (running.putIfAbsent(channel, task) != null) {
            throw new ConflictException("该任务正在进行中");
        }
        bus.open(channel);
        try {
            ingestExecutor.execute(() -> {
                try {
                    body.run(task);
                } catch (Exception exception) {
                    bus.publish(channel, Map.of("type", "error", "message",
                            exception.getMessage() == null
                                    ? exception.getClass().getSimpleName() : exception.getMessage()));
                } finally {
                    bus.terminate(channel);
                    running.remove(channel);
                }
            });
        } catch (RuntimeException exception) {
            bus.terminate(channel);
            running.remove(channel);
            throw exception;
        }
    }

    /**
     * 文档入库一次运行:行上的 run 标记是本次的写围栏——判滞巡检判败或重试接手都会换掉它,
     * 之后本线程的落库一律不生效、结果作废;取消与围栏都经进展心跳观察,在下一个安全边界停下。
     */
    private void runDocumentPipeline(RunningTask task, long kbId, KbDocumentEntity document,
                                     String llmKey, String mineruToken, String fileUrl) throws Exception {
        String channel = documentChannel(document.getId());
        String runToken = RunFence.newToken();
        RunFence.Heartbeat heartbeat = new RunFence.Heartbeat(clock,
                () -> documents.beat(document.getId(), runToken, now()) == 1);
        BooleanSupplier stop = () -> {
            heartbeat.beat();
            return task.cancelled.get() || heartbeat.lost();
        };
        try {
            String index = requireActiveIndex(kbId);

            document.start(runToken, now());
            requireMutation(documents.updateById(document), "文档状态更新未生效");
            bus.publish(channel, Map.of("type", "stage", "stage", "parsing", "message", "正在解析文档…"));
            String text = parser.parse(document.getName(), fileUrl, mineruToken,
                    message -> {
                        heartbeat.beat();
                        bus.publish(channel, Map.of("type", "parse_progress", "message", message));
                    },
                    stop);

            bus.publish(channel, Map.of("type", "stage", "stage", "chunking", "message", "正在分块…"));
            List<Chunker.Chunk> pieces = chunker.chunk(text);
            if (pieces.isEmpty()) {
                throw new BadRequestException("文档没有可入库的文本内容");
            }
            List<KbChunkEntity> stored = replaceChunks(document.getId(), pieces);
            // 旧向量先清(重试场景),再写新的
            es.deleteByDocumentId(index, document.getId());

            document.indexing(now());
            writeFenced(document, runToken, heartbeat);
            bus.publish(channel, Map.of("type", "stage", "stage", "indexing",
                    "message", "共 " + stored.size() + " 个分块,正在向量化…"));
            indexChunks(index, document.getId(), stored, llmKey,
                    (current, total) -> {
                        heartbeat.beat();
                        bus.publish(channel, Map.of("type", "embedding_progress",
                                "current", current, "total", total));
                    },
                    stop);

            document.markReady(stored.size(), knowledgeBaseService.currentSignature(), now());
            writeFenced(document, runToken, heartbeat);
            bus.publish(channel, Map.of("type", "done",
                    "documentId", document.getId(), "chunkCount", stored.size()));
        } catch (Exception exception) {
            document.markError(exception.getMessage(), now());
            fencedUpdate(document, runToken);
            throw exception;
        }
    }

    private void writeFenced(KbDocumentEntity document, String runToken, RunFence.Heartbeat heartbeat) {
        if (fencedUpdate(document, runToken) == 0) {
            heartbeat.markLost();
            throw new IllegalStateException(RESULT_DISCARDED);
        }
    }

    private int fencedUpdate(KbDocumentEntity document, String runToken) {
        return documents.update(document, new LambdaUpdateWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getId, document.getId())
                .eq(KbDocumentEntity::getRunToken, runToken));
    }

    /**
     * 当前生效的物理索引;还没有(首份文档)就建一个并抢指针——并发的两次首次入库各建各的,
     * 指针只让第一个成功者写入,输家删掉自己的、用赢家的。
     */
    private String requireActiveIndex(long kbId) {
        KnowledgeBaseEntity current = knowledgeBases.selectById(kbId);
        if (current == null) {
            throw new ConflictException("知识库已被删除,入库中止");
        }
        if (current.getActiveIndexName() != null) {
            return current.getActiveIndexName();
        }
        String signature = knowledgeBaseService.currentSignature();
        String candidate = EmbeddingSignature.newIndexName(kbId, signature);
        es.createIndex(candidate, properties.embeddingDimension());
        if (knowledgeBases.activateIndexIfEmpty(kbId, signature, candidate) == 1) {
            return candidate;
        }
        es.deleteIndexIfExists(candidate);
        KnowledgeBaseEntity winner = knowledgeBases.selectById(kbId);
        if (winner == null || winner.getActiveIndexName() == null) {
            throw new ConflictException("知识库索引状态已变化,请重试");
        }
        return winner.getActiveIndexName();
    }

    private void runRebuild(RunningTask task, KnowledgeBaseEntity kb, List<KbDocumentEntity> readyDocuments,
                            String llmKey, String newSignature, String newIndex) throws Exception {
        String channel = rebuildChannel(kb.getId());
        String oldIndex = kb.getActiveIndexName();
        try {
            es.createIndex(newIndex, properties.embeddingDimension());
            for (int i = 0; i < readyDocuments.size(); i++) {
                if (task.cancelled.get()) {
                    throw new IllegalStateException("重建已中止");
                }
                KbDocumentEntity document = readyDocuments.get(i);
                bus.publish(channel, Map.of("type", "document_start",
                        "documentId", document.getId(), "name", document.getName(),
                        "index", i + 1, "total", readyDocuments.size()));
                List<KbChunkEntity> stored = chunks.selectList(
                        new LambdaQueryWrapper<KbChunkEntity>()
                                .eq(KbChunkEntity::getDocumentId, document.getId())
                                .orderByAsc(KbChunkEntity::getSeq));
                indexChunks(newIndex, document.getId(), stored, llmKey,
                        (current, total) -> bus.publish(channel, Map.of("type", "embedding_progress",
                                "current", current, "total", total)),
                        task.cancelled::get);
                updateDocumentSignature(document, newSignature);
            }
            activateIndex(kb.getId(), newSignature, newIndex);
            bus.publish(channel, Map.of("type", "done", "total", readyDocuments.size()));
        } catch (Exception exception) {
            deleteIndexQuietly(newIndex);
            throw exception;
        }
        // 指针已切换,旧物理索引成为垃圾;清理失败只记日志,不能连累已成功的重建
        if (oldIndex != null && !oldIndex.equals(newIndex)) {
            deleteIndexQuietly(oldIndex);
        }
    }

    private void deleteIndexQuietly(String index) {
        try {
            es.deleteIndexIfExists(index);
        } catch (RuntimeException exception) {
            log.warn("ES 索引 {} 清理失败,留待人工处理", index, exception);
        }
    }

    // ---- 分块与向量化 ----------------------------------------------------------

    /**
     * 先清后写(重试场景自愈);中途失败文档停在非 ready 态,不会被检索到。
     * 在自己的事务里先锁文档行:索引进行中文档被删除时不留孤儿切片(数据库不设外键)。
     */
    private List<KbChunkEntity> replaceChunks(long documentId, List<Chunker.Chunk> pieces) {
        return transactions.execute(status -> {
            if (documents.selectForUpdate(documentId) == null) {
                throw new ConflictException("文档已被删除，索引中止");
            }
            chunks.delete(new LambdaQueryWrapper<KbChunkEntity>()
                    .eq(KbChunkEntity::getDocumentId, documentId));
            List<KbChunkEntity> stored = new ArrayList<>(pieces.size());
            for (Chunker.Chunk piece : pieces) {
                KbChunkEntity entity = new KbChunkEntity(documentId, piece.seq(), piece.section(),
                        piece.content());
                requireMutation(chunks.insert(entity), "分块写入未生效");
                stored.add(entity);
            }
            return stored;
        });
    }

    private void indexChunks(String index, long documentId, List<KbChunkEntity> stored,
                             String llmKey, ProgressListener progress,
                             BooleanSupplier cancelled) {
        // 每批向量化后写 ES 并上报进度;上游单次请求的条数上限由传输层自行分批
        int batchSize = properties.embeddingBatchSize();
        List<EsClient.ChunkDoc> pendingDocs = new ArrayList<>(BULK_BATCH);
        for (int from = 0; from < stored.size(); from += batchSize) {
            if (cancelled.getAsBoolean()) {
                throw new IllegalStateException("入库已中止");
            }
            List<KbChunkEntity> batch = stored.subList(from, Math.min(from + batchSize, stored.size()));
            List<float[]> vectors;
            try {
                vectors = llm.embed(llmKey, properties.embeddingModel(), properties.embeddingDimension(),
                        batch.stream().map(KbChunkEntity::getContent).toList());
            } catch (RuntimeException exception) {
                throw new IllegalStateException("向量化失败(第 " + (from / batchSize + 1) + "/"
                        + ((stored.size() + batchSize - 1) / batchSize) + " 批): "
                        + exception.getMessage(), exception);
            }
            for (int i = 0; i < batch.size(); i++) {
                KbChunkEntity chunk = batch.get(i);
                pendingDocs.add(new EsClient.ChunkDoc(documentId, chunk.getSeq(),
                        chunk.getSection(), chunk.getContent(), vectors.get(i)));
            }
            if (pendingDocs.size() >= BULK_BATCH) {
                es.bulkIndex(index, pendingDocs);
                pendingDocs.clear();
            }
            progress.accept(Math.min(from + batchSize, stored.size()), stored.size());
        }
        if (!pendingDocs.isEmpty()) {
            es.bulkIndex(index, pendingDocs);
        }
    }

    private interface ProgressListener {
        void accept(int current, int total);
    }

    // ---- 状态落库(任务线程,各自独立短事务) -------------------------------------

    private void activateIndex(long kbId, String signature, String indexName) {
        KnowledgeBaseEntity kb = knowledgeBases.selectById(kbId);
        if (kb == null) {
            throw new ConflictException("知识库已被删除,重建中止");
        }
        kb.activateIndex(signature, indexName, now());
        requireMutation(knowledgeBases.updateById(kb), "知识库索引状态更新未生效");
    }

    private void updateDocumentSignature(KbDocumentEntity document, String signature) {
        document.updateSignature(signature, now());
        requireMutation(documents.updateById(document), "文档签名更新未生效");
    }

    // ---- 校验 -----------------------------------------------------------------

    private void requireSignatureCurrent(KnowledgeBaseEntity kb) {
        String current = knowledgeBaseService.currentSignature();
        if (kb.getActiveSignature() != null && !kb.getActiveSignature().equals(current)) {
            throw new ConflictException("向量模型配置已变化,请先重建索引再添加文档");
        }
    }

    private void requireNoRebuildRunning(long kbId) {
        if (running.containsKey(rebuildChannel(kbId))) {
            throw new ConflictException("该知识库正在重建索引,请等重建完成后再操作文档");
        }
    }

    /** 重建前置:该库不能有在途的入库任务(内存登记)或运行态文档(重启遗留,由心跳对账判败) */
    private void requireNoIngestRunning(long kbId) {
        if (running.containsKey(rebuildChannel(kbId))) {
            throw new ConflictException("该任务正在进行中");
        }
        boolean documentTaskRunning = running.values().stream().anyMatch(task -> task.kbId == kbId);
        boolean documentStateRunning = documents.exists(new LambdaQueryWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getKnowledgeBaseId, kbId)
                .in(KbDocumentEntity::getState, DocumentState.PARSING, DocumentState.INDEXING));
        if (documentTaskRunning || documentStateRunning) {
            throw new ConflictException("有文档正在入库,请等它完成(或中止)后再重建索引");
        }
    }

    private MaterialView requireIndexableMaterial(long courseId, long materialId) {
        MaterialView material = materials.getTrusted(courseId, materialId);
        if (!DocumentParser.supported(material.name())) {
            throw new BadRequestException(
                    "暂不支持该文件类型,可入库的类型:pdf、docx、pptx、xlsx、md、txt");
        }
        return material;
    }

    private SseEmitter settledEmitter(Map<String, Object> event) {
        SseEmitter emitter = new SseEmitter(0L);
        try {
            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(event)));
            emitter.complete();
        } catch (IOException | IllegalStateException exception) {
            // 客户端已断开,无需处理
        }
        return emitter;
    }

    private static boolean isPdf(String name) {
        return name.toLowerCase().endsWith(".pdf");
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static void requireMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new ConflictException(message);
        }
    }
}
