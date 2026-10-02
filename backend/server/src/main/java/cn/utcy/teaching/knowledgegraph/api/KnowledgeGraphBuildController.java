package cn.utcy.teaching.knowledgegraph.api;

import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphService.GraphView;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphBuildService;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphBuildService.BuildDetailView;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphBuildService.BuildView;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphBuildService.PagePreviewView;
import cn.utcy.teaching.knowledgegraph.domain.BuildPreview;
import cn.utcy.teaching.knowledgegraph.application.KnowledgeGraphBuildService.TocEntryPayload;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 教材构建知识图谱的教师端端点。进度经 SSE attach(断线重连不杀任务),
 * SSE 事件不经 orval 客户端消费(记录在案偏离,见 frontend/src/features/knowledgegraph/kgBuildStream.ts)。
 */
@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/knowledge-graph-builds")
public class KnowledgeGraphBuildController {

    private final KnowledgeGraphBuildService builds;

    public KnowledgeGraphBuildController(KnowledgeGraphBuildService builds) {
        this.builds = builds;
    }

    public record CreateBuildRequest(@NotNull @Min(1) Long materialId) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public BuildView create(
            @PathVariable @Min(1) long courseId,
            @Valid @RequestBody CreateBuildRequest request
    ) {
        return builds.create(courseId, request.materialId());
    }

    @GetMapping
    public List<BuildView> list(@PathVariable @Min(1) long courseId) {
        return builds.list(courseId);
    }

    @GetMapping("/{buildId}")
    public BuildDetailView get(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long buildId
    ) {
        return builds.get(courseId, buildId);
    }

    /** 构建进度(SSE attach):stage/parse_progress/toc_ready/progress/extracted/status/error */
    @GetMapping(value = "/{buildId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
            schema = @Schema(type = "string")))
    public SseEmitter events(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long buildId
    ) {
        return builds.attachEvents(courseId, buildId);
    }

    public record SaveTocRequest(
            @NotEmpty @Size(max = 2000) List<@Valid TocEntryItem> entries
    ) {
        public record TocEntryItem(
                @Size(max = 64) String number,
                @NotBlank @Size(max = 255) String title,
                @Min(1) @Max(6) int level,
                @Min(1) int page,
                @Min(1) int endPage
        ) {
        }
    }

    /** 保存教师确认的目录(会重建抽取小节;抽取后再改目录需重新抽取) */
    @PutMapping("/{buildId}/toc")
    public BuildDetailView saveToc(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long buildId,
            @Valid @RequestBody SaveTocRequest request
    ) {
        builds.saveToc(courseId, buildId, request.entries().stream()
                .map(item -> new TocEntryPayload(item.number(), item.title(),
                        item.level(), item.page(), item.endPage()))
                .toList());
        return builds.get(courseId, buildId);
    }

    @GetMapping("/{buildId}/pages/{page}")
    public PagePreviewView pagePreview(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long buildId,
            @PathVariable @Min(1) int page
    ) {
        return builds.pagePreview(courseId, buildId, page);
    }

    @PostMapping("/{buildId}/extract")
    public BuildView extract(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long buildId
    ) {
        return builds.startExtraction(courseId, buildId);
    }

    @PostMapping("/{buildId}/merge")
    public BuildView merge(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long buildId
    ) {
        return builds.mergeIgnoringFailed(courseId, buildId);
    }

    @PostMapping("/{buildId}/retry-parse")
    public BuildView retryParse(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long buildId
    ) {
        return builds.retryParse(courseId, buildId);
    }

    @GetMapping("/{buildId}/preview")
    public BuildPreview preview(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long buildId
    ) {
        return builds.preview(courseId, buildId);
    }

    public record CompleteBuildRequest(
            @NotBlank @Size(max = 128) String name
    ) {
    }

    @PostMapping("/{buildId}/complete")
    public GraphView complete(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long buildId,
            @Valid @RequestBody CompleteBuildRequest request
    ) {
        return builds.complete(courseId, buildId, request.name());
    }

    @PostMapping("/{buildId}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long buildId
    ) {
        builds.cancel(courseId, buildId);
    }

    @DeleteMapping("/{buildId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long buildId
    ) {
        builds.delete(courseId, buildId);
    }
}
