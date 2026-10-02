package cn.utcy.teaching.blockcoding.api;

import cn.utcy.teaching.blockcoding.application.BlockCodingProjectService;
import cn.utcy.teaching.blockcoding.application.BlockCodingProjectService.ProjectView;
import cn.utcy.teaching.blockcoding.application.ChatSessions;
import cn.utcy.teaching.blockcoding.application.ChatViews.SessionView;
import cn.utcy.teaching.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/blockcoding/projects")
public class BlockCodingProjectController {
    private final BlockCodingProjectService projects;
    private final ChatSessions sessions;

    public BlockCodingProjectController(BlockCodingProjectService projects, ChatSessions sessions) {
        this.projects = projects;
        this.sessions = sessions;
    }

    @PostMapping
    public ProjectView create(@PathVariable @Min(1) long courseId, @Valid @RequestBody CreateProjectRequest request) {
        return projects.create(courseId, request.name());
    }

    @GetMapping
    public PageResponse<ProjectView> list(
            @PathVariable @Min(1) long courseId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return projects.list(courseId, page, size);
    }

    @GetMapping("/{id}")
    public ProjectView get(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long id) {
        return projects.get(courseId, id);
    }

    @PatchMapping("/{id}")
    public ProjectView rename(
            @PathVariable @Min(1) long courseId, @PathVariable @Min(1) long id,
            @Valid @RequestBody RenameProjectRequest request
    ) {
        return projects.rename(courseId, id, request.name());
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long id) {
        projects.delete(courseId, id);
    }

    @PutMapping(value = "/{id}/file", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ProjectView saveFile(
            @PathVariable @Min(1) long courseId, @PathVariable @Min(1) long id, @RequestBody byte[] content
    ) {
        return projects.saveFile(courseId, id, content);
    }

    @GetMapping(value = "/{id}/file", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<byte[]> loadFile(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long id) {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"project-" + id + ".sb3\"")
                .body(projects.loadFile(courseId, id));
    }

    @GetMapping("/{id}/chat-session")
    public SessionView chatSession(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long id) {
        return sessions.open(courseId, id);
    }

    public record CreateProjectRequest(@NotBlank @Size(max = 100) String name) {
    }

    public record RenameProjectRequest(@NotBlank @Size(max = 100) String name) {
    }
}
