package cn.utcy.teaching.question.api;

import cn.utcy.teaching.question.application.CourseQuestionApplicationService;
import cn.utcy.teaching.question.application.CourseQuestionApplicationService.ActiveAttemptView;
import cn.utcy.teaching.question.application.CourseQuestionApplicationService.AttemptResult;
import cn.utcy.teaching.question.application.CourseQuestionApplicationService.CourseQuestionDetail;
import cn.utcy.teaching.question.application.CourseQuestionApplicationService.CourseQuestionView;
import cn.utcy.teaching.question.application.CourseQuestionApplicationService.ItemAnswer;
import cn.utcy.teaching.question.application.CourseQuestionApplicationService.LearningQuestionView;
import cn.utcy.teaching.question.application.CourseQuestionApplicationService.SaveItem;
import cn.utcy.teaching.question.application.CourseQuestionApplicationService.SaveQuestion;
import cn.utcy.teaching.question.application.CourseQuestionApplicationService.StudentResultView;
import cn.utcy.teaching.question.domain.CourseQuestionType;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
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

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/questions")
public class CourseQuestionController {

    private final CourseQuestionApplicationService questions;

    public CourseQuestionController(CourseQuestionApplicationService questions) {
        this.questions = questions;
    }

    public record SaveItemRequest(
            @NotNull(message = "题型不能为空") CourseQuestionType type,
            @NotBlank(message = "题干不能为空")
            @Size(max = 100000, message = "题干不能超过 100000 个字符")
            String stemMarkdown,
            JsonNode options,
            @NotNull(message = "标准答案不能为空") JsonNode answer,
            @NotNull(message = "解析不能为空")
            @Size(max = 100000, message = "解析不能超过 100000 个字符")
            String analysisMarkdown,
            @NotNull(message = "分值不能为空") Double score) {

        SaveItem toCommand() {
            return new SaveItem(type, stemMarkdown, options, answer, analysisMarkdown, score);
        }
    }

    public record SaveCourseQuestionRequest(
            @NotBlank(message = "试题标题不能为空")
            @Size(max = 255, message = "试题标题不能超过 255 个字符")
            String title,
            /* 限时(分钟),null 为不限时 */
            @Min(1) @Max(CourseQuestionApplicationService.MAX_TIME_LIMIT_MINUTES) Integer timeLimitMinutes,
            @NotNull(message = "是否可重做不能为空") Boolean allowRetake,
            @NotNull(message = "是否公开答案不能为空") Boolean revealAnswers,
            @NotEmpty(message = "试题至少要有一道题")
            @Size(max = CourseQuestionApplicationService.MAX_ITEMS, message = "试题最多包含 100 道题")
            List<@NotNull @Valid SaveItemRequest> items) {

        SaveQuestion toCommand() {
            return new SaveQuestion(title, timeLimitMinutes, allowRetake, revealAnswers,
                    items.stream().map(SaveItemRequest::toCommand).toList());
        }
    }

    public record ItemAnswerRequest(@NotNull @Min(1) Long itemId, JsonNode answer) {
    }

    public record SubmitAttemptRequest(
            @NotNull @Size(max = CourseQuestionApplicationService.MAX_ITEMS) List<@NotNull @Valid ItemAnswerRequest> answers) {
    }

    // ---- 教师端 ----

    @GetMapping
    public List<CourseQuestionView> list(@PathVariable @Min(1) long courseId) {
        return questions.list(courseId);
    }

    @GetMapping("/{questionId}/management")
    public CourseQuestionDetail getForManagement(@PathVariable @Min(1) long courseId,
                                                 @PathVariable @Min(1) long questionId) {
        return questions.get(courseId, questionId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseQuestionDetail create(@PathVariable @Min(1) long courseId,
                                       @Valid @RequestBody SaveCourseQuestionRequest request) {
        return questions.create(courseId, request.toCommand());
    }

    @PutMapping("/{questionId}")
    public CourseQuestionDetail update(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long questionId,
                                       @Valid @RequestBody SaveCourseQuestionRequest request) {
        return questions.update(courseId, questionId, request.toCommand());
    }

    @DeleteMapping("/{questionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long questionId) {
        questions.delete(courseId, List.of(questionId));
    }

    /** 批量删除,一个事务内全成或全败 */
    @PostMapping("/deletions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMany(@PathVariable @Min(1) long courseId, @Valid @RequestBody QuestionDeletionRequest request) {
        questions.delete(courseId, request.ids());
    }

    public record QuestionDeletionRequest(@NotEmpty List<@Min(1) Long> ids) {
    }

    @GetMapping("/{questionId}/results")
    public List<StudentResultView> results(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long questionId) {
        return questions.results(courseId, questionId);
    }

    @GetMapping("/{questionId}/results/{accountId}/attempts")
    public List<AttemptResult> studentAttempts(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long questionId,
                                               @PathVariable @Min(1) long accountId) {
        return questions.studentAttempts(courseId, questionId, accountId);
    }

    // ---- 学生端 ----

    @GetMapping("/{questionId}")
    public LearningQuestionView get(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long questionId) {
        return questions.getForLearning(courseId, questionId);
    }

    @PostMapping("/{questionId}/attempts")
    @ResponseStatus(HttpStatus.CREATED)
    public ActiveAttemptView startAttempt(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long questionId) {
        return questions.startAttempt(courseId, questionId);
    }

    @PostMapping("/{questionId}/attempts/{attemptId}/submit")
    public AttemptResult submitAttempt(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long questionId,
                                       @PathVariable @Min(1) long attemptId,
                                       @Valid @RequestBody SubmitAttemptRequest request) {
        return questions.submitAttempt(courseId, questionId, attemptId,
                request.answers().stream().map(a -> new ItemAnswer(a.itemId(), a.answer())).toList());
    }

    @GetMapping("/{questionId}/attempts")
    public List<AttemptResult> myAttempts(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long questionId) {
        return questions.myAttempts(courseId, questionId);
    }

    @GetMapping("/{questionId}/attempts/{attemptId}")
    public AttemptResult attempt(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long questionId,
                                 @PathVariable @Min(1) long attemptId) {
        return questions.getAttempt(courseId, questionId, attemptId);
    }
}
