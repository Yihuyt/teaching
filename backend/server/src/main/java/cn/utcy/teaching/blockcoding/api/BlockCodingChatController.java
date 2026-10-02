package cn.utcy.teaching.blockcoding.api;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.blockcoding.application.ChatSessions;
import cn.utcy.teaching.blockcoding.application.ChatTurn;
import cn.utcy.teaching.blockcoding.application.ChatViews.MessageView;
import cn.utcy.teaching.blockcoding.application.ChatViews.Quote;
import cn.utcy.teaching.blockcoding.application.ChatViews.ScriptTextView;
import cn.utcy.teaching.blockcoding.application.HarvestPayload;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/v1/blockcoding")
public class BlockCodingChatController {
    private final ChatSessions sessions;
    private final ChatTurn turn;

    public BlockCodingChatController(ChatSessions sessions, ChatTurn turn) {
        this.sessions = sessions;
        this.turn = turn;
    }

    @DeleteMapping("/chat-sessions/{sessionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable long sessionId) {
        sessions.delete(sessionId);
    }

    @GetMapping("/chat-sessions/{sessionId}/messages")
    public List<MessageView> messages(@PathVariable long sessionId) {
        return sessions.messages(sessionId);
    }

    @PostMapping(value = "/chat-sessions/{sessionId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
            schema = @Schema(type = "string")))
    public SseEmitter sendMessage(@PathVariable long sessionId, @Valid @RequestBody SendMessageRequest request) {
        return turn.send(sessionId, request.content(), request.harvest(), request.quotes(), request.mode());
    }

    @PostMapping("/chat-sessions/{sessionId}/browser-tool-results")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void browserToolResult(@PathVariable long sessionId, @Valid @RequestBody BrowserToolResultRequest request) {
        turn.completeBrowserTool(sessionId, request.callId(), request.ok(), request.result(), request.error());
    }

    @PostMapping("/chat-sessions/{sessionId}/messages/{messageId}/revert")
    public MessageView revert(@PathVariable long sessionId, @PathVariable long messageId) {
        return sessions.markReverted(sessionId, messageId);
    }

    public record BrowserToolResultRequest(
            @NotBlank String callId,
            boolean ok,
            @Schema(nullable = true) JsonNode result,
            @Schema(nullable = true) @Size(max = 500) String error
    ) {
    }

    @PostMapping("/scripts/text")
    public ScriptTextView scriptText(@Valid @RequestBody ScriptTextRequest request) {
        return turn.scriptText(request.sprite(), request.xml());
    }

    public record ScriptTextRequest(@NotBlank @Size(max = 100) String sprite, @NotBlank @Size(max = 200_000) String xml) {
    }

    public record SendMessageRequest(
            @NotBlank @Size(max = 8000) String content,
            @Schema(nullable = true, description = "作品此刻的快照:修改模式必带;讲解模式不读作品,不带") @Valid HarvestPayload harvest,
            @Size(max = 5) List<@Valid Quote> quotes,
            @Schema(nullable = true) @Size(max = 10) String mode
    ) {
    }
}
