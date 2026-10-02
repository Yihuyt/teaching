package cn.utcy.teaching.knowledgegraph.application;

import cn.utcy.teaching.shared.util.BoundedParallel;
import cn.utcy.teaching.ai.llm.AiUnavailableException;
import cn.utcy.teaching.ai.llm.LlmCalls;
import cn.utcy.teaching.ai.llm.ModelConfig;
import cn.utcy.teaching.ai.llm.PromptLoader;
import cn.utcy.teaching.ai.structured.SchemaRegistry;
import cn.utcy.teaching.ai.structured.StructuredGenerator;
import cn.utcy.teaching.knowledgegraph.domain.BuildPreview;
import cn.utcy.teaching.knowledgegraph.domain.EdgeKind;
import cn.utcy.teaching.knowledgegraph.domain.EntityMerger;
import cn.utcy.teaching.knowledgegraph.domain.GraphAssembler;
import cn.utcy.teaching.knowledgegraph.domain.GraphRepairer;
import cn.utcy.teaching.knowledgegraph.domain.GraphRules;
import cn.utcy.teaching.knowledgegraph.domain.SectionStatus;
import cn.utcy.teaching.knowledgegraph.domain.TocEntry;
import cn.utcy.teaching.knowledgegraph.domain.TocOutline;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionEntity;
import cn.utcy.teaching.knowledgegraph.infrastructure.BuildSectionMapper;
import cn.utcy.teaching.knowledgegraph.infrastructure.KnowledgegraphProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 抽取管线(在构建任务线程内编排,模型调用在小节执行器上并行):
 * 每节「摘要 → 抽取」是一个独立任务,节间零依赖,在途 ≤ sectionConcurrency,每节完成即落库(重试只跑非 done 节);
 * 单节失败记录后继续,结束时汇总失败节。合并阶段:跨节合并(精确 + bigram 候选 + LLM 保守裁决,裁决分批并行)
 * → 端点与代码绑定重写为规范名、自环剔除 → 前置去环 → 父级章节摘要(自底向上,同层并行)→ 装配为入库文档。
 */
@Service
public class GraphExtractionService {

    private static final String PROMPTS = "knowledgegraph/prompts";
    private static final int MERGE_PAIRS_PER_BATCH = 40;
    /** 传输故障 / 限流的退避重试:2s → 6s → 18s,共 4 次;模型输出问题由 StructuredGenerator 的回喂闭环处理 */
    private static final RetryTemplate RETRY = RetryTemplate.builder()
            .maxAttempts(4)
            .exponentialBackoff(2_000, 3.0, 30_000)
            .retryOn(AiUnavailableException.class)
            .build();

    private final StructuredGenerator structured;
    private final SchemaRegistry schemas;
    private final PromptLoader prompts;
    private final ModelConfig llmModel;
    private final BuildSectionMapper sections;
    private final ObjectMapper objectMapper;
    private final TaskExecutor sectionExecutor;
    private final KnowledgegraphProperties properties;
    private final LlmCalls llm;

    public GraphExtractionService(StructuredGenerator structured,
                                  @Qualifier("knowledgegraphSchemas") SchemaRegistry schemas,
                                  PromptLoader prompts,
                                  @Qualifier("knowledgegraphLlmModel") ModelConfig llmModel,
                                  BuildSectionMapper sections,
                                  ObjectMapper objectMapper,
                                  @Qualifier("kgTaskExecutor") TaskExecutor sectionExecutor,
                                  KnowledgegraphProperties properties,
                                  LlmCalls llm) {
        this.structured = structured;
        this.schemas = schemas;
        this.prompts = prompts;
        this.llmModel = llmModel;
        this.sections = sections;
        this.objectMapper = objectMapper;
        this.sectionExecutor = sectionExecutor;
        this.llm = llm;
        this.properties = properties;
    }

    public interface Progress {
        void accept(int done, int total, String sectionTitle);
    }

    public static boolean needsExtraction(BuildSectionEntity row) {
        return row.getStatus() != SectionStatus.DONE && row.getStatus() != SectionStatus.IGNORED;
    }

    /**
     * 并行抽取全部未完成小节;单节失败记录后继续,返回失败小节清单(标题 — 原因)。
     * 取消:不再提交新小节,在途的跑完后抛「任务已取消」。
     */
    public List<String> extractSections(String apiKey, String bookTitle, List<BuildSectionEntity> rows,
                                        Map<Integer, String> texts, Progress progress, BooleanSupplier cancelled) {
        int total = rows.size();
        AtomicInteger done = new AtomicInteger((int) rows.stream().filter(row -> !needsExtraction(row)).count());
        List<String> failures = Collections.synchronizedList(new ArrayList<>());
        List<Supplier<Void>> tasks = new ArrayList<>();
        for (BuildSectionEntity row : rows) {
            if (!needsExtraction(row)) {
                continue;
            }
            tasks.add(() -> {
                try {
                    extractOne(apiKey, bookTitle, row, texts.get(row.getSectionIndex()));
                    progress.accept(done.incrementAndGet(), total, row.getTitle());
                } catch (RuntimeException exception) {
                    String message = describe(exception);
                    row.failed(message);
                    sections.updateById(row);
                    failures.add(row.getTitle() + " — " + message);
                }
                return null;
            });
        }
        BoundedParallel.run(sectionExecutor, properties.sectionConcurrency(), tasks, cancelled);
        return List.copyOf(failures);
    }

    private void extractOne(String apiKey, String bookTitle, BuildSectionEntity row, String text) {
        row.running();
        sections.updateById(row);
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("抽取子片正文缺失");
        }
        JsonNode summary = withRetry(() -> summarizeLeaf(apiKey, bookTitle, row.getPath(), text));
        JsonNode extraction = withRetry(() -> extractSection(apiKey, bookTitle, row, summary, text));
        row.done(summary.toString(), extraction.toString());
        sections.updateById(row);
    }

    private JsonNode summarizeLeaf(String apiKey, String bookTitle, String path, String text) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("bookTitle", bookTitle);
        vars.put("path", path);
        vars.put("sectionText", text);
        vars.put("childSummaries", "");
        vars.put("schemaJson", schemas.rawSchema("summarize"));
        PromptLoader.Prompt prompt = prompts.build(PROMPTS, "summarize", vars);
        return structured.generate(new StructuredGenerator.Request<>(
                apiKey, llmModel, "summarize",
                schemas.rawSchema("summarize"), schemas.validator("summarize"),
                prompt.system(), prompt.user(), 3,
                StructuredGenerator.Refined::value,
                null)).value();
    }

    private JsonNode extractSection(String apiKey, String bookTitle, BuildSectionEntity row,
                                    JsonNode summary, String text) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("bookTitle", bookTitle);
        vars.put("sectionTitle", row.getTitle());
        vars.put("path", row.getPath());
        vars.put("leafSummary", summary.path("summary").asText(""));
        vars.put("sectionText", text);
        vars.put("schemaJson", schemas.rawSchema("kg-extract"));
        PromptLoader.Prompt prompt = prompts.build(PROMPTS, "kg-extract", vars);
        return structured.generate(new StructuredGenerator.Request<>(
                apiKey, llmModel, "kg-extract",
                schemas.rawSchema("kg-extract"), schemas.validator("kg-extract"),
                prompt.system(), prompt.user(), 3,
                this::refineExtraction,
                null)).value();
    }

    /**
     * 语义校验(schema 表达不了,回喂修)。关系端点不要求在本次输出中:跨节关系
     * (如本节的「逻辑表达式」→ 3.3 节的「嵌套if语句」)在全书合并时按名对齐,
     * 对不上的由装配器警告并跳过——小节层面硬卡会与"每节知识点上限"互相顶死
     * (补端点超上限,删端点又悬空,模型在两条规则间来回撞墙)。
     */
    private StructuredGenerator.Refined<JsonNode> refineExtraction(JsonNode parsed) {
        List<String> errors = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        parsed.path("knowledgePoints").forEach(kp -> names.add(kp.path("name").asText()));
        int index = 0;
        for (JsonNode relation : parsed.path("relations")) {
            index++;
            String source = relation.path("source").asText();
            String target = relation.path("target").asText();
            if (source.equals(target)) {
                errors.add("relations[" + index + "] 不允许自环: " + source);
            }
        }
        index = 0;
        for (JsonNode code : parsed.path("codeExamples")) {
            index++;
            for (JsonNode bound : code.path("bindKpNames")) {
                if (!names.contains(bound.asText())) {
                    errors.add("codeExamples[" + index + "] 绑定的知识点「" + bound.asText()
                            + "」不在本次输出中");
                }
            }
        }
        return errors.isEmpty()
                ? StructuredGenerator.Refined.value(parsed)
                : StructuredGenerator.Refined.errors(errors);
    }

    // ---- 合并 + 修复 + 装配 ----------------------------------------------------

    public BuildPreview finalizeGraph(String apiKey, String bookTitle, List<TocEntry> entries,
                                      List<BuildSectionEntity> rows, Consumer<String> onStage,
                                      BooleanSupplier cancelled) {
        // 已忽略的小节不参与合并,其目录条目仍生成章节节点(内容为空)
        List<BuildSectionEntity> included = rows.stream()
                .filter(row -> row.getStatus() != SectionStatus.IGNORED)
                .toList();
        for (BuildSectionEntity row : included) {
            if (row.getStatus() != SectionStatus.DONE) {
                throw new IllegalStateException("仍有小节未完成抽取：" + row.getTitle());
            }
        }
        onStage.accept("正在跨节合并知识点…");
        EntityMerger merger = new EntityMerger();
        List<PendingRelation> rawRelations = new ArrayList<>();
        List<GraphAssembler.CodeExample> codeExamples = new ArrayList<>();
        for (BuildSectionEntity row : included) {
            JsonNode result = readJson(row.getResultJson(), "抽取结果");
            for (JsonNode kp : result.path("knowledgePoints")) {
                merger.add(new EntityMerger.RawKp(
                        kp.path("name").asText(),
                        kp.path("kpType").asText(),
                        kp.path("definition").asText(),
                        textList(kp.path("aliases")),
                        textList(kp.path("evidenceQuotes")),
                        row.getSectionIndex()));
            }
            for (JsonNode relation : result.path("relations")) {
                rawRelations.add(new PendingRelation(
                        relation.path("source").asText(),
                        relation.path("target").asText(),
                        "PREREQUISITE".equals(relation.path("kind").asText())
                                ? EdgeKind.PREREQUISITE : EdgeKind.RELATED,
                        relation.path("evidence").asText("")));
            }
            for (JsonNode code : result.path("codeExamples")) {
                codeExamples.add(new GraphAssembler.CodeExample(
                        code.path("title").asText(),
                        code.path("language").asText(),
                        code.path("code").asText(),
                        code.path("explanation").asText(),
                        textList(code.path("evidenceQuotes")),
                        textList(code.path("bindKpNames"))));
            }
        }

        List<String> warnings = new ArrayList<>();
        List<EntityMerger.CandidatePair> candidates = new ArrayList<>(merger.exactMergeAndCollectCandidates());
        candidates.addAll(semanticCandidates(apiKey, merger, candidates, warnings));
        if (!candidates.isEmpty()) {
            onStage.accept("有 " + candidates.size() + " 对相似知识点，正在裁决是否合并…");
            adjudicate(apiKey, candidates, cancelled).forEach(merger::applyMerge);
        }
        EntityMerger.MergeResult mergeResult = merger.finalizeMerge();

        List<GraphRepairer.Relation> relations = new ArrayList<>();
        int selfLoops = 0;
        for (PendingRelation raw : rawRelations) {
            String source = mergeResult.nameToCanonical().getOrDefault(raw.source(), raw.source());
            String target = mergeResult.nameToCanonical().getOrDefault(raw.target(), raw.target());
            if (source.equals(target)) {
                selfLoops++;
                continue;
            }
            relations.add(new GraphRepairer.Relation(source, target, raw.kind(), raw.evidence()));
        }
        if (selfLoops > 0) {
            warnings.add("合并后 " + selfLoops + " 条关系变为自环，已剔除");
        }
        codeExamples = rewriteCodeBindings(codeExamples, mergeResult.nameToCanonical());
        requireNotCancelled(cancelled);
        GraphRepairer.RemovalResult acyclic = GraphRepairer.removePrerequisiteCycles(dedupeRelations(relations));
        if (!acyclic.removedEdges().isEmpty()) {
            warnings.add("前置依赖存在环，已拆除 " + acyclic.removedEdges().size() + " 条最弱依赖边："
                    + String.join("；", acyclic.removedEdges()));
        }

        onStage.accept("正在生成章节摘要…");
        Map<Integer, String> sectionSummaries = aggregateSummaries(apiKey, bookTitle, entries, included, cancelled);

        Map<Integer, Integer> sliceToEntry = new LinkedHashMap<>();
        Set<Integer> extractedLeaves = new LinkedHashSet<>();
        for (BuildSectionEntity row : included) {
            sliceToEntry.put(row.getSectionIndex(), row.getEntryIndex());
            extractedLeaves.add(row.getEntryIndex());
        }
        Map<Integer, Integer> kpFirstEntry = new LinkedHashMap<>();
        for (int k = 0; k < mergeResult.knowledgePoints().size(); k++) {
            int firstSlice = mergeResult.knowledgePoints().get(k).sectionIndexes().get(0);
            Integer entryIndex = sliceToEntry.get(firstSlice);
            if (entryIndex == null) {
                throw new IllegalStateException("抽取子片 " + firstSlice + " 无法定位目录条目");
            }
            kpFirstEntry.put(k, entryIndex);
        }

        onStage.accept("正在装配图谱…");
        BuildPreview assembled = GraphAssembler.assemble(entries, sectionSummaries,
                mergeResult.knowledgePoints(), kpFirstEntry, extractedLeaves, acyclic.relations(), codeExamples);
        if (assembled.nodes().size() > GraphRules.MAX_NODES || assembled.edges().size() > GraphRules.MAX_EDGES) {
            throw new IllegalStateException("图谱规模超出上限：节点 " + assembled.nodes().size()
                    + "/" + GraphRules.MAX_NODES + "，关系 " + assembled.edges().size() + "/" + GraphRules.MAX_EDGES
                    + "。请在目录确认页剔除部分小节，或拆分为多个图谱分别构建");
        }
        warnings.addAll(assembled.warnings());
        return new BuildPreview(assembled.nodes(), assembled.edges(), warnings);
    }

    /**
     * 去环前合并完全相同的重复边(同起点、同终点、同类型,跨节重复抽出的产物):
     * 每条边只留一条、取最长证据,去环按"一条边一条命"评估,拆几条报几条;
     * 同一对「相关 + 前置」的跨类型取舍不在此处,仍由装配器裁决。
     */
    static List<GraphRepairer.Relation> dedupeRelations(List<GraphRepairer.Relation> relations) {
        Map<String, GraphRepairer.Relation> unique = new LinkedHashMap<>();
        for (GraphRepairer.Relation relation : relations) {
            String key = relation.sourceName() + "\0" + relation.targetName() + "\0" + relation.kind();
            GraphRepairer.Relation existing = unique.get(key);
            if (existing == null || evidenceLength(relation) > evidenceLength(existing)) {
                unique.put(key, relation);
            }
        }
        return List.copyOf(unique.values());
    }

    private static int evidenceLength(GraphRepairer.Relation relation) {
        return relation.evidence() == null ? 0 : relation.evidence().length();
    }

    /** 绑定名与关系端点走同一张 原始名→规范名 重写表,合并改名后代码示例不失联;并成同一知识点的绑定去重 */
    static List<GraphAssembler.CodeExample> rewriteCodeBindings(List<GraphAssembler.CodeExample> codeExamples,
                                                                Map<String, String> nameToCanonical) {
        List<GraphAssembler.CodeExample> rewritten = new ArrayList<>(codeExamples.size());
        for (GraphAssembler.CodeExample code : codeExamples) {
            List<String> bindings = code.bindKpNames().stream()
                    .map(name -> nameToCanonical.getOrDefault(name, name))
                    .distinct()
                    .toList();
            rewritten.add(new GraphAssembler.CodeExample(code.title(), code.language(), code.code(),
                    code.explanation(), code.evidenceQuotes(), bindings));
        }
        return rewritten;
    }

    private record PendingRelation(String source, String target, EdgeKind kind, String evidence) {
    }

    /** 语义召回的标定参数(2026-08-29 真机实验):0.85 会漏缩写对(IDE 0.79),0.75+top3+封顶 控噪声量 */
    private static final double SEMANTIC_THRESHOLD = 0.75;
    /** 百炼文本向量单次请求上限 10 条,超了直接被拒 */
    private static final int SEMANTIC_TOP_K = 3;
    private static final int SEMANTIC_EMBED_DIMENSION = 256;
    private static final int SEMANTIC_DEFINITION_CHARS = 100;

    /**
     * 语义召回:簇根的「名称:释义」嵌入(text-embedding-v4,向量不落库)取近邻送裁决,
     * 专补字面与别名都对不上的同义(无别名的中英同名、口语化改写)。
     * 嵌入服务不可用时按明确规则可见降级:本次只按字面与别名召回,预览警告一条。
     */
    private List<EntityMerger.CandidatePair> semanticCandidates(String apiKey, EntityMerger merger,
                                                                List<EntityMerger.CandidatePair> existing,
                                                                List<String> warnings) {
        List<EntityMerger.RootRef> roots = merger.recallRoots();
        if (roots.size() < 2) {
            return List.of();
        }
        List<String> texts = roots.stream()
                .map(root -> root.name() + "：" + GraphRules.truncate(root.definition(), SEMANTIC_DEFINITION_CHARS))
                .toList();
        List<float[]> vectors;
        try {
            vectors = llm.embed(apiKey, properties.embeddingModel(), SEMANTIC_EMBED_DIMENSION, texts);
        } catch (AiUnavailableException exception) {
            warnings.add("语义召回不可用（" + exception.getMessage() + "），本次仅按字面与别名召回相似知识点");
            return List.of();
        }
        return EntityMerger.semanticCandidates(roots, vectors, existing,
                SEMANTIC_THRESHOLD, SEMANTIC_TOP_K, roots.size() * 2, existing.size());
    }

    private static String aliasText(java.util.List<String> aliases) {
        return aliases.isEmpty() ? "" : "(别名: " + String.join("/", aliases) + ")";
    }

    /** LLM 保守裁决(分批并行):输出缺失的对 = 不合并(显式规则) */
    private List<EntityMerger.CandidatePair> adjudicate(String apiKey, List<EntityMerger.CandidatePair> candidates,
                                                        BooleanSupplier cancelled) {
        Map<Integer, EntityMerger.CandidatePair> byId = new HashMap<>();
        candidates.forEach(pair -> byId.put(pair.pairId(), pair));
        List<Supplier<List<EntityMerger.CandidatePair>>> batches = new ArrayList<>();
        for (int from = 0; from < candidates.size(); from += MERGE_PAIRS_PER_BATCH) {
            List<EntityMerger.CandidatePair> batch = candidates.subList(
                    from, Math.min(from + MERGE_PAIRS_PER_BATCH, candidates.size()));
            batches.add(() -> adjudicateBatch(apiKey, batch, byId));
        }
        List<EntityMerger.CandidatePair> merged = new ArrayList<>();
        BoundedParallel.run(sectionExecutor, properties.sectionConcurrency(), batches, cancelled).forEach(merged::addAll);
        return merged;
    }

    private List<EntityMerger.CandidatePair> adjudicateBatch(String apiKey, List<EntityMerger.CandidatePair> batch,
                                                             Map<Integer, EntityMerger.CandidatePair> byId) {
        StringBuilder pairsText = new StringBuilder();
        for (EntityMerger.CandidatePair pair : batch) {
            pairsText.append("- pairId=").append(pair.pairId())
                    .append(":「").append(pair.leftName()).append("」")
                    .append(aliasText(pair.leftAliases()))
                    .append("(释义: ").append(pair.leftDefinition()).append(") vs「")
                    .append(pair.rightName()).append("」")
                    .append(aliasText(pair.rightAliases()))
                    .append("(释义: ").append(pair.rightDefinition()).append(")\n");
        }
        PromptLoader.Prompt prompt = prompts.build(PROMPTS, "entity-merge",
                Map.of("pairs", pairsText.toString(),
                        "schemaJson", schemas.rawSchema("entity-merge")));
        JsonNode decisions = withRetry(() -> structured.generate(new StructuredGenerator.Request<>(
                apiKey, llmModel, "entity-merge",
                schemas.rawSchema("entity-merge"), schemas.validator("entity-merge"),
                prompt.system(), prompt.user(), 2,
                StructuredGenerator.Refined::value,
                null)).value());
        List<EntityMerger.CandidatePair> merged = new ArrayList<>();
        for (JsonNode decision : decisions.path("decisions")) {
            if (decision.path("merge").asBoolean(false)) {
                EntityMerger.CandidatePair pair = byId.get(decision.path("pairId").asInt(-1));
                if (pair != null) {
                    merged.add(pair);
                }
            }
        }
        return merged;
    }

    private Map<Integer, String> aggregateSummaries(String apiKey, String bookTitle, List<TocEntry> entries,
                                                    List<BuildSectionEntity> rows, BooleanSupplier cancelled) {
        Map<Integer, String> summaries = new ConcurrentHashMap<>();
        for (BuildSectionEntity row : rows) {
            String summary = readJson(row.getSummaryJson(), "摘要").path("summary").asText("");
            summaries.merge(row.getEntryIndex(), summary, (a, b) -> a + "\n" + b);
        }
        // 按 level 从深到浅分层;同一层的父摘要互不依赖
        TreeMap<Integer, List<Integer>> byLevel = new TreeMap<>(Collections.reverseOrder());
        for (int i = 0; i < entries.size(); i++) {
            if (!summaries.containsKey(i)) {
                byLevel.computeIfAbsent(entries.get(i).level(), key -> new ArrayList<>()).add(i);
            }
        }
        for (List<Integer> level : byLevel.values()) {
            List<Supplier<Map.Entry<Integer, String>>> tasks = new ArrayList<>();
            for (int index : level) {
                List<String> childLines = new ArrayList<>();
                int depth = entries.get(index).level();
                for (int child = index + 1; child < entries.size()
                        && entries.get(child).level() > depth; child++) {
                    if (entries.get(child).level() == depth + 1 && summaries.containsKey(child)) {
                        childLines.add("- " + TocOutline.display(entries.get(child)) + ": "
                                + summaries.get(child));
                    }
                }
                if (childLines.isEmpty()) {
                    continue;
                }
                String path = TocOutline.path(entries, index);
                tasks.add(() -> Map.entry(index, summarizeParent(apiKey, bookTitle, path, childLines)));
            }
            BoundedParallel.run(sectionExecutor, properties.sectionConcurrency(), tasks, cancelled)
                    .forEach(entry -> summaries.put(entry.getKey(), entry.getValue()));
        }
        return summaries;
    }

    private String summarizeParent(String apiKey, String bookTitle, String path, List<String> childLines) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("bookTitle", bookTitle);
        vars.put("path", path);
        vars.put("sectionText", "");
        vars.put("childSummaries", String.join("\n", childLines));
        vars.put("schemaJson", schemas.rawSchema("summarize"));
        PromptLoader.Prompt prompt = prompts.build(PROMPTS, "summarize", vars);
        return withRetry(() -> structured.generate(new StructuredGenerator.Request<>(
                apiKey, llmModel, "summarize",
                schemas.rawSchema("summarize"), schemas.validator("summarize"),
                prompt.system(), prompt.user(), 2,
                StructuredGenerator.Refined::value,
                null)).value()).path("summary").asText("");
    }

    // ---- 重试 --------------------------------------------------------------------

    private static <T> T withRetry(Supplier<T> call) {
        return RETRY.execute((RetryCallback<T, RuntimeException>) context -> call.get());
    }

    private static void requireNotCancelled(BooleanSupplier cancelled) {
        BoundedParallel.requireNotCancelled(cancelled);
    }

    private static String describe(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    private JsonNode readJson(String json, String what) {
        if (json == null) {
            throw new IllegalStateException(what + "缺失");
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(what + "不是有效 JSON", exception);
        }
    }

    private static List<String> textList(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }
}
