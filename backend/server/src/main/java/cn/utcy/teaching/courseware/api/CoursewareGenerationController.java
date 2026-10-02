package cn.utcy.teaching.courseware.api;

import cn.utcy.teaching.courseware.application.StageGenerationService;
import cn.utcy.teaching.courseware.domain.SceneBrief;
import cn.utcy.teaching.courseware.application.StageGenerationService.ConfirmedOutline;
import cn.utcy.teaching.courseware.application.StageGenerationService.MaterialImageRef;
import cn.utcy.teaching.courseware.application.StageGenerationService.SceneOutline;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 课件生成流水线的流式端点(教师管理面):出大纲 → 按确认的大纲生成 → 单页重生成。
 * SSE 事件不经 orval 客户端消费(事件类型见 frontend/src/features/courseware/coursewareStream.ts)。
 */
@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/coursewares/{coursewareId}")
public class CoursewareGenerationController {

    private final StageGenerationService generation;

    public CoursewareGenerationController(StageGenerationService generation) {
        this.generation = generation;
    }

    public record OutlineRequest(
            @NotBlank(message = "教学需求不能为空") @Size(max = 4000) String requirement,
            /* 总页数(含封面);不填由模型规划 */
            @Min(StageGenerationService.MIN_SCENE_COUNT) @Max(StageGenerationService.MAX_SCENE_COUNT) Integer sceneCount,
            @Min(0) @Max(StageGenerationService.MAX_QUIZ_COUNT) Integer quizCount,
            @Min(0) @Max(StageGenerationService.MAX_INTERACTIVE_COUNT) Integer interactiveCount) {
    }

    /**
     * 出大纲(SSE):课件名下已上传的素材是大纲的内容依据。
     * 事件 trace{message} / outline{title,scenes[],images[]} / error{message};大纲不落库,由教师确认后再生成。
     */
    @PostMapping(value = "/outline", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
            schema = @Schema(type = "string")))
    public SseEmitter outline(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long coursewareId,
                              @Valid @RequestBody OutlineRequest request) {
        return generation.outlineSse(courseId, coursewareId, request.requirement().strip(),
                request.sceneCount(), request.quizCount(), request.interactiveCount());
    }

    public record MaterialImageRefPayload(@Min(1) long materialId, @NotBlank String imageId) {
    }

    public record IllustrationPayload(@NotBlank(message = "AI 配图缺少画面描述")
                                      @Size(max = SceneBrief.Illustration.MAX_PROMPT_CHARS) String prompt,
                                      @Pattern(regexp = "16:9|4:3|1:1|3:4") String aspectRatio) {
    }

    /** widgetType / widgetOutline 只有交互页携带;images 与 illustration 只有讲解页携带(素材图每页最多 8 张,AI 配图最多 1 张) */
    public record SceneOutlinePayload(@NotBlank @Size(max = 200) String title,
                                      @NotBlank @Pattern(regexp = "content|quiz|interactive") String type,
                                      @NotBlank String preset,
                                      @NotBlank @Size(max = 2000) String summary,
                                      @Size(max = SceneBrief.MAX_KEY_POINTS) List<@NotBlank @Size(max = SceneBrief.MAX_KEY_POINT_CHARS) String> keyPoints,
                                      @Pattern(regexp = "simulation|diagram|game|visualization3d") String widgetType,
                                      JsonNode widgetOutline,
                                      @Size(max = StageGenerationService.MAX_SCENE_IMAGES)
                                      List<@Valid MaterialImageRefPayload> images,
                                      @Valid IllustrationPayload illustration) {
    }

    public record GenerateStageRequest(@NotBlank @Size(max = 255) String title,
                                       @NotEmpty @Size(max = StageGenerationService.MAX_SCENE_COUNT)
                                       List<@Valid SceneOutlinePayload> scenes) {
    }

    /**
     * 按确认后的大纲生成整份课件(SSE):清空课件后各页并行生成、按页序逐页落库。
     * 事件 scene_start / trace / scene_done / scene_failed / done{version,total,failed} / error。
     */
    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
            schema = @Schema(type = "string")))
    public SseEmitter generate(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long coursewareId,
                               @Valid @RequestBody GenerateStageRequest request) {
        List<SceneOutline> scenes = request.scenes().stream()
                .map(item -> new SceneOutline(item.title().strip(), item.type(), item.preset(), item.summary().strip(),
                        item.keyPoints() == null ? List.of() : item.keyPoints(),
                        item.widgetType(),
                        item.widgetOutline() == null || item.widgetOutline().isNull() ? null : item.widgetOutline(),
                        item.images() == null ? List.of() : item.images().stream()
                                .map(ref -> new MaterialImageRef(ref.materialId(), ref.imageId()))
                                .toList(),
                        item.illustration() == null ? null : SceneBrief.Illustration.normalize(
                                new SceneBrief.Illustration(item.illustration().prompt(), item.illustration().aspectRatio()))))
                .toList();
        return generation.generateSse(courseId, coursewareId,
                new ConfirmedOutline(request.title(), scenes));
    }

    public record SpeechRequest(
            /* missing=只补没有讲稿的页;all=全部重写 */
            @NotBlank @Pattern(regexp = "missing|all", message = "scope 只能是 missing 或 all") String scope) {
    }

    /**
     * 生成讲稿与动作(SSE):按教师改定的内容给各页写讲稿,逐页落库。
     * 事件 scene_start / trace / scene_done / scene_failed / done{version,total,failed} / error
     */
    @PostMapping(value = "/speech", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
            schema = @Schema(type = "string")))
    public SseEmitter generateSpeech(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long coursewareId,
                                     @Valid @RequestBody SpeechRequest request) {
        return generation.generateSpeechSse(courseId, coursewareId, request.scope());
    }

    public record RegenerateSceneRequest(
            /* content=整页内容重做(讲稿清空);speech=只重讲稿 */
            @NotBlank @Pattern(regexp = "content|speech", message = "scope 只能是 content 或 speech") String scope,
            @Size(max = 2000) String instruction) {
    }

    /** 单页重生成(SSE):完成即落库。事件 scene_start / trace / scene_done / done{version} / error */
    @PostMapping(value = "/scenes/{sceneId}/regenerate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
            schema = @Schema(type = "string")))
    public SseEmitter regenerateScene(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long coursewareId,
                                      @PathVariable @NotBlank @Size(max = 32) String sceneId,
                                      @Valid @RequestBody RegenerateSceneRequest request) {
        return generation.regenerateSceneSse(courseId, coursewareId, sceneId, request.scope(), request.instruction());
    }
}
