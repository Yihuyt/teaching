package cn.utcy.teaching.courseware.api;

import cn.utcy.teaching.courseware.application.CoursewareApplicationService;
import cn.utcy.teaching.courseware.application.CoursewareApplicationService.CoursewarePlayView;
import cn.utcy.teaching.courseware.application.CoursewareQaService;
import cn.utcy.teaching.courseware.application.LearningRecordService;
import cn.utcy.teaching.courseware.application.QuizGradingService;
import cn.utcy.teaching.courseware.application.QuizGradingService.MyAttempt;
import cn.utcy.teaching.courseware.application.QuizGradingService.Verdict;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 智能课堂的学生学习面:课程成员即可学习已生成页面的课件(课件自成「智能课堂」栏目,不经课程内容编排)。
 * 播放视图不含测验答案;判分与作答记录全部在服务端。
 */
@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/coursewares/{coursewareId}")
public class CoursewareLearningController {

    private final CoursewareApplicationService coursewares;
    private final QuizGradingService grading;
    private final LearningRecordService learning;
    private final CoursewareQaService qa;

    public CoursewareLearningController(CoursewareApplicationService coursewares,
                                        QuizGradingService grading,
                                        LearningRecordService learning,
                                        CoursewareQaService qa) {
        this.coursewares = coursewares;
        this.grading = grading;
        this.learning = learning;
        this.qa = qa;
    }

    @GetMapping("/play")
    public CoursewarePlayView play(@PathVariable @Min(1) long courseId,
                                   @PathVariable @Min(1) long coursewareId) {
        return coursewares.getPlayView(courseId, coursewareId);
    }

    public record QuizAttemptRequest(
            @NotBlank String sceneId,
            @NotBlank String blockId,
            @NotEmpty(message = "至少选择一个选项") List<@NotBlank String> chosen) {
    }

    @PostMapping("/quiz-attempts")
    public Verdict submitQuizAttempt(@PathVariable @Min(1) long courseId,
                                     @PathVariable @Min(1) long coursewareId,
                                     @Valid @RequestBody QuizAttemptRequest request) {
        return grading.grade(courseId, coursewareId, request.sceneId(), request.blockId(),
                request.chosen());
    }

    @GetMapping("/quiz-attempts/mine")
    public List<MyAttempt> myQuizAttempts(@PathVariable @Min(1) long courseId,
                                          @PathVariable @Min(1) long coursewareId) {
        return grading.myAttempts(courseId, coursewareId);
    }

    public record QuestionRequest(
            @NotBlank(message = "sceneId 不能为空") String sceneId,
            @NotBlank(message = "问题不能为空")
            @Size(max = 500, message = "问题最长 500 字") String question) {
    }

    /**
     * 课堂提问(SSE 流式):answer_delta 增量 → (校验打回 retry) → done 带完整回答
     * (文本 + 视觉动作 + 可选语音);问答对按学生落库。
     */
    @PostMapping(value = "/questions", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
            schema = @Schema(type = "string")))
    public SseEmitter askQuestion(@PathVariable @Min(1) long courseId,
                                  @PathVariable @Min(1) long coursewareId,
                                  @Valid @RequestBody QuestionRequest request) {
        return qa.askSse(courseId, coursewareId, request.sceneId(), request.question().trim());
    }

    public record SceneViewRequest(@NotBlank(message = "sceneId 不能为空") String sceneId) {
    }

    @PostMapping("/scene-views")
    @ResponseStatus(HttpStatus.CREATED)
    public void recordSceneView(@PathVariable @Min(1) long courseId,
                               @PathVariable @Min(1) long coursewareId,
                               @Valid @RequestBody SceneViewRequest request) {
        learning.recordSceneView(courseId, coursewareId, request.sceneId());
    }
}
