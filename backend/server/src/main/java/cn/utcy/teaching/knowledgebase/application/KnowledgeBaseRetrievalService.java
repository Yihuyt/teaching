package cn.utcy.teaching.knowledgebase.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.ai.llm.LlmCalls;
import cn.utcy.teaching.knowledgebase.infrastructure.RrfFusion;
import cn.utcy.teaching.knowledgebase.infrastructure.EsClient;
import cn.utcy.teaching.knowledgebase.infrastructure.KbChunkEntity;
import cn.utcy.teaching.knowledgebase.infrastructure.KbChunkMapper;
import cn.utcy.teaching.knowledgebase.infrastructure.KbDocumentMapper;
import cn.utcy.teaching.knowledgebase.infrastructure.KnowledgeBaseEntity;
import cn.utcy.teaching.knowledgebase.infrastructure.KnowledgebaseProperties;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索内核(模块对外唯一检索入口):query 向量化 → BM25 + kNN 双路 → RRF 融合
 * → MySQL 回填正文。问答与 AI 出题共用。**不做访问控制**——调用方自行完成鉴权
 * (先例:资料的 createDownloadTrusted)。embedding 按课程负责人的密钥计费。
 */
@Service
public class KnowledgeBaseRetrievalService {

    /** 检索 query 长度上限:向量化与 BM25 都不需要长文 */
    public static final int MAX_QUERY_CHARS = 2000;

    private final KnowledgeBaseService knowledgeBaseService;
    private final KbDocumentMapper documents;
    private final KbChunkMapper chunks;
    private final EsClient es;
    private final LlmCalls llm;
    private final CourseAiKeys aiKeys;
    private final KnowledgebaseProperties properties;

    public KnowledgeBaseRetrievalService(KnowledgeBaseService knowledgeBaseService,
                                        KbDocumentMapper documents, KbChunkMapper chunks,
                                        EsClient es, LlmCalls llm,
                                        CourseAiKeys aiKeys,
                                        KnowledgebaseProperties properties) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.documents = documents;
        this.chunks = chunks;
        this.es = es;
        this.llm = llm;
        this.aiKeys = aiKeys;
        this.properties = properties;
    }

    public record RetrievedPassage(long kbId, String kbName, String documentName, String section,
                                   String content) {
    }

    public record KnowledgeBaseRef(long id, String name) {
    }

    /**
     * 校验知识库属于课程且索引就绪(活动签名 = 当前向量配置),不就绪明确抛冲突——
     * 出题等调用方在开流前调用,失败不进 SSE。
     */
    @Transactional(readOnly = true)
    public List<KnowledgeBaseRef> requireReady(long courseId, List<Long> kbIds) {
        List<KnowledgeBaseRef> refs = new ArrayList<>();
        for (Long kbId : kbIds) {
            KnowledgeBaseEntity kb = requireReadyEntity(courseId, kbId);
            refs.add(new KnowledgeBaseRef(kb.getId(), kb.getName()));
        }
        return refs;
    }

    /**
     * 在一个知识库内检索,按 RRF 融合顺序返回前 topK 段。
     * 刻意不开数据库事务:中间是向量化与 ES 两跳外部调用,包在事务里只是白占连接;
     * 各次读取单独一致即可(ES 与 MySQL 短暂不一致的悬空 id 在回填时跳过)。
     */
    public List<RetrievedPassage> retrieve(long courseId, long kbId, String rawQuery, int topK) {
        KnowledgeBaseEntity kb = requireReadyEntity(courseId, kbId);
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            throw new IllegalArgumentException("检索 query 不能为空");
        }
        if (query.length() > MAX_QUERY_CHARS) {
            query = query.substring(0, MAX_QUERY_CHARS);
        }
        String index = kb.getActiveIndexName();
        String apiKey = aiKeys.llmKeyForCourse(courseId);
        return search(apiKey, kb, index, query, topK);
    }

    private KnowledgeBaseEntity requireReadyEntity(long courseId, long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseService.require(courseId, kbId);
        String signature = knowledgeBaseService.currentSignature();
        if (kb.getActiveSignature() == null || !kb.getActiveSignature().equals(signature)) {
            throw new ConflictException("知识库「" + kb.getName() + "」索引未就绪,请先在知识库页完成入库或重建索引");
        }
        return kb;
    }

    private List<RetrievedPassage> search(String apiKey, KnowledgeBaseEntity kb, String index, String query,
                                  int topK) {
        float[] queryVector = llm.embed(apiKey, properties.embeddingModel(),
                properties.embeddingDimension(), List.of(query)).get(0);
        int candidates = topK * 2;
        List<String> bm25 = es.searchBm25(index, query, candidates);
        List<String> knn = es.searchKnn(index, queryVector, candidates);
        List<String> fused = RrfFusion.fuse(List.of(bm25, knn), topK);
        if (fused.isEmpty()) {
            return List.of();
        }

        // _id = "{documentId}-{seq}" → 回 MySQL 取正文与文档名
        Map<Long, Map<Integer, String>> wanted = new LinkedHashMap<>();
        for (String id : fused) {
            int dash = id.lastIndexOf('-');
            long documentId = Long.parseLong(id.substring(0, dash));
            int seq = Integer.parseInt(id.substring(dash + 1));
            wanted.computeIfAbsent(documentId, k -> new LinkedHashMap<>()).put(seq, id);
        }
        Map<Long, String> documentNames = new HashMap<>();
        documents.selectByIds(wanted.keySet())
                .forEach(doc -> documentNames.put(doc.getId(), doc.getName()));
        Map<String, RetrievedPassage> byId = new HashMap<>();
        for (Map.Entry<Long, Map<Integer, String>> entry : wanted.entrySet()) {
            List<KbChunkEntity> rows = chunks.selectList(new LambdaQueryWrapper<KbChunkEntity>()
                    .eq(KbChunkEntity::getDocumentId, entry.getKey())
                    .in(KbChunkEntity::getSeq, entry.getValue().keySet()));
            for (KbChunkEntity row : rows) {
                byId.put(entry.getValue().get(row.getSeq()), new RetrievedPassage(
                        kb.getId(), kb.getName(),
                        documentNames.getOrDefault(entry.getKey(), "未知文档"),
                        row.getSection(), row.getContent()));
            }
        }
        List<RetrievedPassage> ordered = new ArrayList<>();
        for (String id : fused) {
            RetrievedPassage passage = byId.get(id);
            if (passage != null) {
                ordered.add(passage);
            }
        }
        return ordered;
    }

    public static String renderContext(List<RetrievedPassage> passages) {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < passages.size(); i++) {
            RetrievedPassage passage = passages.get(i);
            context.append("[source-").append(i + 1).append("] ").append(passage.documentName());
            if (passage.section() != null && !passage.section().isBlank()) {
                context.append(" › ").append(passage.section());
            }
            context.append('\n').append(passage.content()).append("\n\n");
        }
        return context.toString().trim();
    }
}
