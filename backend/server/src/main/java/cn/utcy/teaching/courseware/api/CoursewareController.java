package cn.utcy.teaching.courseware.api;

import cn.utcy.teaching.courseware.application.CoursewareApplicationService;
import cn.utcy.teaching.courseware.application.CoursewareApplicationService.CoursewareDetail;
import cn.utcy.teaching.courseware.application.CoursewareApplicationService.CoursewareSummary;
import cn.utcy.teaching.courseware.application.StageEditService;
import cn.utcy.teaching.courseware.application.LearningRecordService;
import cn.utcy.teaching.courseware.application.LearningRecordService.LearningReport;
import cn.utcy.teaching.courseware.application.PptxExportService;
import cn.utcy.teaching.courseware.application.PptxExportService.ExportFile;
import cn.utcy.teaching.courseware.application.TtsApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 智能课堂(课件)的教师管理面:课件行的增删、发布、逐操作即时编辑、语音合成、导出、学习报告。
 * 课件文档(stage)以完整 JSON 承载,其结构由服务端课件校验器保证(courseware/schemas),不在 OpenAPI 里重复建模;
 * 编辑操作(ops)同理——词汇表见 EditOp.java 与前端 ops.ts。
 */
@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/coursewares")
public class CoursewareController {

    private static final MediaType PPTX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation");

    private final CoursewareApplicationService coursewares;
    private final StageEditService edits;
    private final TtsApplicationService tts;
    private final PptxExportService export;
    private final LearningRecordService learning;

    public CoursewareController(CoursewareApplicationService coursewares, StageEditService edits,
                                TtsApplicationService tts, PptxExportService export,
                                LearningRecordService learning) {
        this.coursewares = coursewares;
        this.edits = edits;
        this.tts = tts;
        this.export = export;
        this.learning = learning;
    }

    @GetMapping
    public List<CoursewareSummary> list(@PathVariable @Min(1) long courseId) {
        return coursewares.list(courseId);
    }

    @GetMapping("/learnable")
    public List<CoursewareSummary> listLearnable(@PathVariable @Min(1) long courseId) {
        return coursewares.listForLearning(courseId);
    }

    @GetMapping("/{coursewareId}")
    public CoursewareDetail get(@PathVariable @Min(1) long courseId,
                                @PathVariable @Min(1) long coursewareId) {
        return coursewares.getForManagement(courseId, coursewareId);
    }

    public record CreateCoursewareRequest(
            @NotBlank(message = "课件标题不能为空") @Size(max = 255) String title) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CoursewareDetail create(@PathVariable @Min(1) long courseId,
                                   @Valid @RequestBody CreateCoursewareRequest request) {
        return coursewares.create(courseId, request.title());
    }

    @DeleteMapping("/{coursewareId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Min(1) long courseId,
                       @PathVariable @Min(1) long coursewareId) {
        coursewares.delete(courseId, coursewareId);
    }

    @PostMapping("/{coursewareId}/publication")
    public CoursewareSummary publish(@PathVariable @Min(1) long courseId,
                                     @PathVariable @Min(1) long coursewareId) {
        return coursewares.publish(courseId, coursewareId);
    }

    @DeleteMapping("/{coursewareId}/publication")
    public CoursewareSummary unpublish(@PathVariable @Min(1) long courseId,
                                       @PathVariable @Min(1) long coursewareId) {
        return coursewares.unpublish(courseId, coursewareId);
    }

    /** ops 每项是一条编辑操作 JSON(判别字段 op;词汇表见 frontend/src/features/courseware/dsl/ops.ts 与 EditOp.java) */
    public record ApplyOpsRequest(@NotEmpty(message = "至少一项操作") @Size(max = 50) List<JsonNode> ops) {
    }

    /** 逐操作即时落库:整批原子生效或整批被拒(400 带逐项错误);返回最新课件 */
    @PutMapping("/{coursewareId}/ops")
    public CoursewareDetail applyOps(@PathVariable @Min(1) long courseId,
                                     @PathVariable @Min(1) long coursewareId,
                                     @Valid @RequestBody ApplyOpsRequest request) {
        return edits.applyForTeacher(courseId, coursewareId, request.ops());
    }

    /** 整课语音合成(SSE)。事件:progress{done,total}/done{coursewareId,version,generated,skipped}/error */
    @PostMapping(value = "/{coursewareId}/tts", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
            schema = @Schema(type = "string")))
    public SseEmitter synthesize(@PathVariable @Min(1) long courseId,
                                 @PathVariable @Min(1) long coursewareId) {
        return tts.synthesizeSse(courseId, coursewareId);
    }

    @PostMapping("/{coursewareId}/export/pptx")
    public ResponseEntity<byte[]> exportPptx(@PathVariable @Min(1) long courseId,
                                             @PathVariable @Min(1) long coursewareId) {
        ExportFile file = export.exportPptx(courseId, coursewareId);
        return ResponseEntity.ok()
                .contentType(PPTX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.filename(), StandardCharsets.UTF_8)
                        .build().toString())
                .body(file.bytes());
    }

    @GetMapping("/{coursewareId}/learning-report")
    public LearningReport learningReport(@PathVariable @Min(1) long courseId,
                                         @PathVariable @Min(1) long coursewareId) {
        return learning.report(courseId, coursewareId);
    }
}
