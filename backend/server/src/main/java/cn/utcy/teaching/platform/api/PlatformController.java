package cn.utcy.teaching.platform.api;

import cn.utcy.teaching.platform.application.PlatformApplicationService;
import cn.utcy.teaching.shared.storage.ObjectStorageStatusProvider.ObjectStorageStatus;
import cn.utcy.teaching.platform.application.PlatformApplicationService.PlatformView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/platform")
public class PlatformController {

    private final PlatformApplicationService platform;

    public PlatformController(PlatformApplicationService platform) {
        this.platform = platform;
    }

    @GetMapping("/public")
    public PlatformView publicSettings() {
        return platform.getPublicSettings();
    }

    @GetMapping("/settings")
    public PlatformView settings() {
        return platform.getSettings();
    }

    @PutMapping("/settings")
    public PlatformView update(@Valid @RequestBody UpdatePlatformRequest request) {
        return platform.update(request.siteName(), request.footerText());
    }

    @org.springframework.web.bind.annotation.PostMapping("/oss-status/deletion-retries")
    public PlatformApplicationService.RetriedDeletions retryFailedDeletions() {
        return platform.retryFailedDeletions();
    }

    @GetMapping("/oss-status")
    public ObjectStorageStatus objectStorageStatus() {
        return platform.checkObjectStorage();
    }

    public record UpdatePlatformRequest(
            @NotBlank @Size(max = 64) String siteName,
            @NotNull @Size(max = 255) String footerText
    ) {
    }
}
