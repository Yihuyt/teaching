package cn.utcy.teaching.notification.api;

import cn.utcy.teaching.shared.web.PageResponse;
import cn.utcy.teaching.notification.application.AnnouncementApplicationService;
import cn.utcy.teaching.notification.application.AnnouncementApplicationService.AnnouncementView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/notifications/announcements")
public class AnnouncementController {

    private final AnnouncementApplicationService announcements;

    public AnnouncementController(AnnouncementApplicationService announcements) {
        this.announcements = announcements;
    }

    @GetMapping
    public PageResponse<AnnouncementView> list(
            @RequestParam(required = false) Long courseId,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return announcements.list(courseId, page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AnnouncementView create(@Valid @RequestBody CreateAnnouncementRequest request) {
        return announcements.create(request.courseId(), request.title(), request.contentMarkdown());
    }

    @PutMapping("/{announcementId}")
    public AnnouncementView update(
            @PathVariable long announcementId,
            @Valid @RequestBody UpdateAnnouncementRequest request
    ) {
        return announcements.update(announcementId, request.title(), request.contentMarkdown());
    }

    @DeleteMapping("/{announcementId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long announcementId) {
        announcements.delete(announcementId);
    }

    public record CreateAnnouncementRequest(
            Long courseId,
            @NotBlank @Size(max = 255) String title,
            @NotNull String contentMarkdown
    ) {
    }

    public record UpdateAnnouncementRequest(
            @NotBlank @Size(max = 255) String title,
            @NotNull String contentMarkdown
    ) {
    }
}
