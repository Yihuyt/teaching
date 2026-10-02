package cn.utcy.teaching.question.api;

import cn.utcy.teaching.question.application.QuestionAttachments;
import cn.utcy.teaching.question.application.QuestionPipelineService;
import cn.utcy.teaching.question.domain.CourseQuestionType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
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

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/questions")
public class CourseQuestionGenerationController {

    private final QuestionPipelineService pipeline;

    public CourseQuestionGenerationController(QuestionPipelineService pipeline) {
        this.pipeline = pipeline;
    }

    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generate(
            @PathVariable @Min(1) long courseId,
            @Valid @RequestBody GenerateQuestionsRequest request
    ) {
        Map<CourseQuestionType, Integer> counts = new EnumMap<>(CourseQuestionType.class);
        counts.put(CourseQuestionType.SINGLE_CHOICE, zeroIfNull(request.singleChoiceCount()));
        counts.put(CourseQuestionType.FILL_IN_BLANK, zeroIfNull(request.fillInBlankCount()));
        counts.put(CourseQuestionType.TRUE_FALSE, zeroIfNull(request.trueFalseCount()));
        return pipeline.generateSse(courseId, request.mode(), request.requirement(), request.knowledgeBaseIds(),
                request.attachmentMaterialIds(), request.examMaterialId(),
                request.maxQuestions(), request.difficulty(), counts);
    }

    private static int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * custom 模式:requirement / 题型数量 / difficulty 必填,attachmentMaterialIds 可选;
     * mimic 模式:examMaterialId 必填(PDF 试卷),maxQuestions 可选(默认 10);两种模式都可挂知识库。
     * 模式相关的必填校验在服务层按 mode 执行。
     */
    public record GenerateQuestionsRequest(
            @NotNull(message = "出题模式不能为空")
            QuestionPipelineService.Mode mode,
            @Size(max = 4000, message = "出题要求不能超过 4000 个字符")
            String requirement,
            @Size(max = QuestionPipelineService.MAX_KNOWLEDGE_BASES, message = "一次最多挂载 5 个知识库")
            List<@NotNull @Min(1) Long> knowledgeBaseIds,
            @Size(max = QuestionAttachments.MAX_FILES, message = "一次最多附加 5 个文件")
            List<@NotNull @Min(1) Long> attachmentMaterialIds,
            @Min(1) Long examMaterialId,
            @Min(1) @Max(QuestionPipelineService.MAX_TOTAL) Integer maxQuestions,
            @Min(0) @Max(10) Integer singleChoiceCount,
            @Min(0) @Max(10) Integer fillInBlankCount,
            @Min(0) @Max(10) Integer trueFalseCount,
            @Pattern(regexp = "easy|medium|hard", message = "难度必须是 easy/medium/hard 之一")
            String difficulty) {
    }
}
