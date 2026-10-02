package cn.utcy.teaching.knowledgebase.api;

import cn.utcy.teaching.knowledgebase.application.DocumentIngestService;
import cn.utcy.teaching.knowledgebase.application.DocumentIngestService.RebuildStarted;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseService;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseService.KbDocumentView;
import cn.utcy.teaching.knowledgebase.application.KnowledgeBaseService.KnowledgeBaseView;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 知识库教师端:CRUD、文档入库与重建(启动即返、后台执行)、进度 attach(SSE)、中止。
 * SSE 事件不经 orval 客户端消费(记录在案偏离,见 frontend/src/features/knowledgebase/knowledgebaseStream.ts)。
 */
@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/knowledge-bases")
public class KnowledgeBaseAdminController {

    private final KnowledgeBaseService knowledgeBases;
    private final DocumentIngestService ingest;

    public KnowledgeBaseAdminController(KnowledgeBaseService knowledgeBases,
                                        DocumentIngestService ingest) {
        this.knowledgeBases = knowledgeBases;
        this.ingest = ingest;
    }

    public record SaveKnowledgeBaseRequest(
            @NotBlank(message = "知识库名称不能为空")
            @Size(max = 100, message = "知识库名称不能超过 100 个字符")
            String name
    ) {
    }

    @GetMapping
    public List<KnowledgeBaseView> list(@PathVariable @Min(1) long courseId) {
        return knowledgeBases.list(courseId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBaseView create(
            @PathVariable @Min(1) long courseId,
            @Valid @RequestBody SaveKnowledgeBaseRequest request
    ) {
        return knowledgeBases.create(courseId, request.name());
    }

    @PutMapping("/{kbId}")
    public KnowledgeBaseView update(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long kbId,
            @Valid @RequestBody SaveKnowledgeBaseRequest request
    ) {
        return knowledgeBases.update(courseId, kbId, request.name());
    }

    @DeleteMapping("/{kbId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long kbId) {
        knowledgeBases.delete(courseId, kbId);
    }

    @GetMapping("/{kbId}/documents")
    public List<KbDocumentView> listDocuments(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long kbId
    ) {
        return knowledgeBases.listDocuments(courseId, kbId);
    }

    public record IngestDocumentRequest(@NotNull @Min(1) Long materialId) {
    }

    @PostMapping("/{kbId}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    public KbDocumentView ingestDocument(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long kbId,
            @Valid @RequestBody IngestDocumentRequest request
    ) {
        return ingest.ingest(courseId, kbId, request.materialId());
    }

    @PostMapping("/{kbId}/documents/{documentId}/retry")
    public KbDocumentView retryDocument(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long kbId,
            @PathVariable @Min(1) long documentId
    ) {
        return ingest.retry(courseId, kbId, documentId);
    }

    /** 文档入库进度 attach(SSE):stage/parse_progress/embedding_progress/done/error;不在跑回放 status */
    @GetMapping(value = "/{kbId}/documents/{documentId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
            schema = @Schema(type = "string")))
    public SseEmitter documentEvents(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long kbId,
            @PathVariable @Min(1) long documentId
    ) {
        return ingest.documentEvents(courseId, kbId, documentId);
    }

    @PostMapping("/{kbId}/documents/{documentId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelDocument(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long kbId,
            @PathVariable @Min(1) long documentId
    ) {
        ingest.cancelDocument(courseId, kbId, documentId);
    }

    @DeleteMapping("/{kbId}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long kbId,
            @PathVariable @Min(1) long documentId
    ) {
        knowledgeBases.deleteDocument(courseId, kbId, documentId);
    }

    @PostMapping("/{kbId}/rebuild")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RebuildStarted rebuild(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long kbId
    ) {
        return ingest.rebuild(courseId, kbId);
    }

    /** 重建进度 attach(SSE):stage/document_start/embedding_progress/done/error;不在跑回放 status */
    @GetMapping(value = "/{kbId}/rebuild/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
            schema = @Schema(type = "string")))
    public SseEmitter rebuildEvents(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long kbId
    ) {
        return ingest.rebuildEvents(courseId, kbId);
    }

    @PostMapping("/{kbId}/rebuild/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelRebuild(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long kbId) {
        ingest.cancelRebuild(courseId, kbId);
    }
}
