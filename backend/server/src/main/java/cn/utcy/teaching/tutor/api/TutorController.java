package cn.utcy.teaching.tutor.api;

import cn.utcy.teaching.shared.web.PageResponse;
import cn.utcy.teaching.tutor.application.TutorAssistantService;
import cn.utcy.teaching.tutor.application.TutorAssistantService.TutorAssistantCard;
import cn.utcy.teaching.tutor.application.TutorAssistantService.TutorAssistantDefaults;
import cn.utcy.teaching.tutor.application.TutorAssistantService.TutorAssistantView;
import cn.utcy.teaching.tutor.application.TutorChatService;
import cn.utcy.teaching.tutor.application.TutorSessionService;
import cn.utcy.teaching.tutor.application.TutorSessionService.TutorAdminSessionView;
import cn.utcy.teaching.tutor.application.TutorSessionService.TutorMessageView;
import cn.utcy.teaching.tutor.application.TutorSessionService.TutorSessionView;
import cn.utcy.teaching.tutor.application.TutorSessionService.TutorTranscriptView;
import cn.utcy.teaching.tutor.infrastructure.TutorAssistantEntity.ModelSettings;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/tutor")
public class TutorController {

    private final TutorAssistantService assistants;
    private final TutorSessionService sessions;
    private final TutorChatService chat;

    public TutorController(TutorAssistantService assistants, TutorSessionService sessions, TutorChatService chat) {
        this.assistants = assistants;
        this.sessions = sessions;
        this.chat = chat;
    }

    public record SaveAssistantRequest(
            @NotBlank(message = "助手名称不能为空")
            @Size(max = 60, message = "助手名称不能超过 60 个字符")
            String name,
            @Size(max = 300, message = "助手简介不能超过 300 个字符")
            String description,
            @Size(max = 4000, message = "补充要求不能超过 4000 个字符")
            String instructions,
            @NotBlank(message = "模型不能为空")
            @Size(max = 100, message = "模型名不能超过 100 个字符")
            String model,
            @NotNull(message = "温度不能为空")
            @DecimalMin(value = "0", message = "温度须在 0 到 2 之间")
            @DecimalMax(value = "2", message = "温度须在 0 到 2 之间")
            Double temperature,
            @NotNull(message = "深度思考开关不能为空") Boolean reasoning,
            @NotNull(message = "工具轮次上限不能为空")
            @Min(value = 1, message = "工具轮次上限须在 1 到 16 之间")
            @Max(value = TutorAssistantService.MAX_ROUNDS_LIMIT, message = "工具轮次上限须在 1 到 16 之间")
            Integer maxRounds,
            @NotNull(message = "对学生开放开关不能为空") Boolean visibleToStudents,
            @NotNull @Size(max = TutorAssistantService.MAX_MOUNTS, message = "一个助手最多挂载 10 个知识库")
            List<@NotNull @Min(1) Long> knowledgeBaseIds) {

        TutorAssistantService.SaveAssistant toCommand() {
            return new TutorAssistantService.SaveAssistant(name, description, instructions,
                    new ModelSettings(model, temperature, reasoning, maxRounds), visibleToStudents,
                    knowledgeBaseIds);
        }
    }

    @GetMapping("/assistants")
    public List<TutorAssistantView> listAssistants(@PathVariable @Min(1) long courseId) {
        return assistants.list(courseId);
    }

    @GetMapping("/assistants/defaults")
    public TutorAssistantDefaults assistantDefaults(@PathVariable @Min(1) long courseId) {
        return assistants.defaults();
    }

    @GetMapping("/assistants/learnable")
    public List<TutorAssistantCard> listLearnableAssistants(@PathVariable @Min(1) long courseId) {
        return assistants.listForStudents(courseId);
    }

    @PostMapping("/assistants")
    @ResponseStatus(HttpStatus.CREATED)
    public TutorAssistantView createAssistant(@PathVariable @Min(1) long courseId,
                                              @Valid @RequestBody SaveAssistantRequest request) {
        return assistants.create(courseId, request.toCommand());
    }

    @PutMapping("/assistants/{assistantId}")
    public TutorAssistantView updateAssistant(@PathVariable @Min(1) long courseId,
                                              @PathVariable @Min(1) long assistantId,
                                              @Valid @RequestBody SaveAssistantRequest request) {
        return assistants.update(courseId, assistantId, request.toCommand());
    }

    @DeleteMapping("/assistants/{assistantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAssistant(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long assistantId) {
        assistants.delete(courseId, assistantId);
    }

    @GetMapping("/assistants/{assistantId}/sessions")
    public List<TutorSessionView> listSessions(@PathVariable @Min(1) long courseId,
                                               @PathVariable @Min(1) long assistantId) {
        return sessions.list(courseId, assistantId);
    }

    @PostMapping("/assistants/{assistantId}/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public TutorSessionView createSession(@PathVariable @Min(1) long courseId,
                                          @PathVariable @Min(1) long assistantId) {
        return sessions.create(courseId, assistantId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long sessionId) {
        sessions.delete(courseId, sessionId);
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public List<TutorMessageView> messages(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long sessionId) {
        return sessions.messages(courseId, sessionId);
    }

    public record TutorSendRequest(
            @NotBlank(message = "问题不能为空")
            @Size(max = TutorChatService.MAX_QUESTION_CHARS, message = "问题不能超过 2000 个字符")
            String question) {
    }

    /**
     * 发送消息(SSE):user_message{id} → seed_sources{sources} → round{callId,phase,role?} /
     * thinking{callId,text} / content{callId,text} / tool{callId,toolCallId,name,phase,args|summary,sources} /
     * notice{message} / heartbeat → done{assistantMessageId,sources} → title{title}? / error{message}
     */
    @PostMapping(value = "/sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
            schema = @Schema(type = "string")))
    public SseEmitter send(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long sessionId,
                           @Valid @RequestBody TutorSendRequest request) {
        return chat.sendSse(courseId, sessionId, request.question());
    }

    @GetMapping("/admin-sessions")
    public PageResponse<TutorAdminSessionView> adminSessions(
            @PathVariable @Min(1) long courseId,
            @RequestParam(required = false) @Min(1) Long assistantId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return sessions.listForCourse(courseId, assistantId, page, size);
    }

    @GetMapping("/admin-sessions/{sessionId}")
    public TutorTranscriptView adminTranscript(@PathVariable @Min(1) long courseId,
                                          @PathVariable @Min(1) long sessionId) {
        return sessions.transcript(courseId, sessionId);
    }
}
