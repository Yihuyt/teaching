package cn.utcy.teaching.knowledgebase.application;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.knowledgebase.domain.DocumentState;
import cn.utcy.teaching.knowledgebase.infrastructure.EmbeddingSignature;
import cn.utcy.teaching.knowledgebase.infrastructure.EsClient;
import cn.utcy.teaching.knowledgebase.infrastructure.KbDocumentEntity;
import cn.utcy.teaching.knowledgebase.infrastructure.KbDocumentMapper;
import cn.utcy.teaching.knowledgebase.infrastructure.KnowledgeBaseEntity;
import cn.utcy.teaching.knowledgebase.infrastructure.KnowledgeBaseMapper;
import cn.utcy.teaching.knowledgebase.infrastructure.KnowledgeBaseRowPurger;
import cn.utcy.teaching.knowledgebase.infrastructure.KnowledgebaseProperties;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/** 知识库 CRUD;索引状态由 active_signature 与当前 embedding 签名对比得出(可见性在课程助手上,不在库上) */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private final KnowledgeBaseMapper knowledgeBases;
    private final KbDocumentMapper documents;
    private final EsClient es;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final KnowledgebaseProperties properties;
    private final ObjectMapper objectMapper;
    private final KnowledgeBaseRowPurger purger;

    public KnowledgeBaseService(KnowledgeBaseMapper knowledgeBases, KbDocumentMapper documents,
                                EsClient es,
                                CourseAccess courseAccess, CurrentActor currentActor,
                                KnowledgebaseProperties properties, ObjectMapper objectMapper,
                                KnowledgeBaseRowPurger purger) {
        this.purger = purger;
        this.knowledgeBases = knowledgeBases;
        this.documents = documents;
        this.es = es;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public String currentSignature() {
        return EmbeddingSignature.of(properties.embeddingModel(), properties.embeddingDimension());
    }

    // ---- 教师端 ---------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<KnowledgeBaseView> list(long courseId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        return knowledgeBases.selectList(new LambdaQueryWrapper<KnowledgeBaseEntity>()
                        .eq(KnowledgeBaseEntity::getCourseId, courseId)
                        .orderByDesc(KnowledgeBaseEntity::getUpdatedAt))
                .stream().map(this::view).toList();
    }

    @Transactional
    public KnowledgeBaseView create(long courseId, String name) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(courseId, name.trim(), now());
        try {
            requireMutation(knowledgeBases.insert(entity), "知识库创建未生效");
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ConflictException("同名知识库已存在");
        }
        return view(entity);
    }

    @Transactional
    public KnowledgeBaseView update(long courseId, long kbId, String name) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeBaseEntity entity = require(courseId, kbId);
        entity.update(name.trim(), now());
        try {
            requireMutation(knowledgeBases.updateById(entity), "知识库状态已变化,更新未生效");
        } catch (org.springframework.dao.DuplicateKeyException exception) {
            throw new ConflictException("同名知识库已存在");
        }
        return view(entity);
    }

    /** ES 物理索引由 purger 在事务提交后清理 */
    @Transactional
    public void delete(long courseId, long kbId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeBaseEntity entity = requireForUpdate(courseId, kbId);
        purger.purgeKnowledgeBases(List.of(entity.getId()));
    }

    @Transactional(readOnly = true)
    public List<KbDocumentView> listDocuments(long courseId, long kbId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeBaseEntity entity = require(courseId, kbId);
        return documents.selectList(new LambdaQueryWrapper<KbDocumentEntity>()
                        .eq(KbDocumentEntity::getKnowledgeBaseId, entity.getId())
                        .orderByDesc(KbDocumentEntity::getUpdatedAt))
                .stream().map(KnowledgeBaseService::documentView).toList();
    }

    @Transactional
    public void deleteDocument(long courseId, long kbId, long documentId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        KnowledgeBaseEntity entity = require(courseId, kbId);
        KbDocumentEntity document = requireDocument(entity.getId(), documentId);
        // 锁文档行:索引任务写切片前也锁它,删除后不再有切片落地
        if (documents.selectForUpdate(document.getId()) == null) {
            throw new NotFoundException("知识库中不存在该文档");
        }
        purger.purgeDocuments(List.of(document.getId()));
        if (entity.getActiveIndexName() != null) {
            String indexName = entity.getActiveIndexName();
            afterCommit(() -> es.deleteByDocumentId(indexName, document.getId()),
                    "知识库文档 " + document.getId() + " 索引清理失败");
        }
    }

    // ---- 课程助手挂载 ----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<KnowledgeBaseMount> describe(long courseId, List<Long> kbIds) {
        String signature = currentSignature();
        List<KnowledgeBaseMount> mounts = new ArrayList<>();
        for (Long kbId : kbIds) {
            KnowledgeBaseEntity kb = require(courseId, kbId);
            mounts.add(new KnowledgeBaseMount(kb.getId(), kb.getName(), signature.equals(kb.getActiveSignature())));
        }
        return mounts;
    }

    public record KnowledgeBaseMount(long id, String name, boolean ready) {
    }

    // ---- 模块内共用 ------------------------------------------------------------

    private KnowledgeBaseEntity requireForUpdate(long courseId, long kbId) {
        KnowledgeBaseEntity entity = knowledgeBases.selectForUpdate(kbId);
        if (entity == null || entity.getCourseId() != courseId) {
            throw new NotFoundException("课程中不存在该知识库");
        }
        return entity;
    }

    /** 事务提交后执行(失败只记日志):外部索引的删除不随数据库事务回滚,只能在提交后做 */
    private static void afterCommit(Runnable action, String failureMessage) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    action.run();
                } catch (RuntimeException exception) {
                    log.warn(failureMessage, exception);
                }
            }
        });
    }

    KnowledgeBaseEntity require(long courseId, long kbId) {
        KnowledgeBaseEntity entity = knowledgeBases.selectOne(
                new LambdaQueryWrapper<KnowledgeBaseEntity>()
                        .eq(KnowledgeBaseEntity::getCourseId, courseId)
                        .eq(KnowledgeBaseEntity::getId, kbId));
        if (entity == null) {
            throw new NotFoundException("课程中不存在该知识库");
        }
        return entity;
    }

    KbDocumentEntity requireDocument(long kbId, long documentId) {
        KbDocumentEntity document = documents.selectOne(
                new LambdaQueryWrapper<KbDocumentEntity>()
                        .eq(KbDocumentEntity::getKnowledgeBaseId, kbId)
                        .eq(KbDocumentEntity::getId, documentId));
        if (document == null) {
            throw new NotFoundException("知识库中不存在该文档");
        }
        return document;
    }

    private KnowledgeBaseView view(KnowledgeBaseEntity entity) {
        long documentCount = documents.selectCount(new LambdaQueryWrapper<KbDocumentEntity>()
                .eq(KbDocumentEntity::getKnowledgeBaseId, entity.getId()));
        String indexStatus = entity.getActiveSignature() == null ? "empty"
                : entity.getActiveSignature().equals(currentSignature()) ? "ready" : "needs_rebuild";
        return new KnowledgeBaseView(entity.getId(), entity.getCourseId(), entity.getName(),
                indexStatus, documentCount,
                entity.getCreatedAt().toInstant(ZoneOffset.UTC),
                entity.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    static KbDocumentView documentView(KbDocumentEntity document) {
        return new KbDocumentView(document.getId(), document.getMaterialId(), document.getName(),
                document.getState(), document.getErrorMessage(), document.getChunkCount(),
                document.getCreatedAt().toInstant(ZoneOffset.UTC),
                document.getUpdatedAt().toInstant(ZoneOffset.UTC));
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    private static void requireMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new ConflictException(message);
        }
    }

    /** indexStatus: empty=尚无索引 / ready=可问答 / needs_rebuild=embedding 配置已变,需重建 */
    public record KnowledgeBaseView(
            long id,
            long courseId,
            String name,
            String indexStatus,
            long documentCount,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record KbDocumentView(
            long id,
            @Schema(nullable = true) Long materialId,
            String name,
            DocumentState state,
            @Schema(nullable = true) String errorMessage,
            int chunkCount,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

}
