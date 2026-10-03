package cn.utcy.teaching.blockcoding.api;

import cn.utcy.teaching.blockcoding.application.CourseBlockCodingConfigService;
import cn.utcy.teaching.blockcoding.application.CourseBlockCodingConfigService.ManagementConfigView;
import cn.utcy.teaching.blockcoding.application.CourseBlockCodingConfigService.MemberConfigView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/blockcoding-config")
public class CourseBlockCodingConfigController {

    private final CourseBlockCodingConfigService config;

    public CourseBlockCodingConfigController(CourseBlockCodingConfigService config) {
        this.config = config;
    }

    @GetMapping
    public MemberConfigView get(@PathVariable long courseId) {
        return config.view(courseId);
    }

    @GetMapping("/management")
    public ManagementConfigView getForManagement(@PathVariable long courseId) {
        return config.viewForManagement(courseId);
    }

    @PutMapping
    public ManagementConfigView update(
            @PathVariable long courseId,
            @Valid @RequestBody UpdateConfigRequest request
    ) {
        return config.update(courseId, request.enabled(), request.tutorPrompt(), request.model());
    }

    public record UpdateConfigRequest(
            @NotNull Boolean enabled,
            @NotNull @Size(max = 4000) String tutorPrompt,
            @NotNull String model
    ) {
    }
}
