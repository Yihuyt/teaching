package cn.utcy.teaching.analytics.api;

import cn.utcy.teaching.analytics.application.AnalyticsApplicationService;
import cn.utcy.teaching.analytics.application.AnalyticsApplicationService.ClassOverview;
import cn.utcy.teaching.analytics.application.AnalyticsApplicationService.StudentReport;
import cn.utcy.teaching.analytics.application.AnalyticsExportService;
import cn.utcy.teaching.shared.actor.CurrentActor;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/analytics")
public class CourseAnalyticsController {

    private final AnalyticsApplicationService analytics;
    private final AnalyticsExportService exports;
    private final CurrentActor currentActor;

    public CourseAnalyticsController(AnalyticsApplicationService analytics,
                                     AnalyticsExportService exports,
                                     CurrentActor currentActor) {
        this.analytics = analytics;
        this.exports = exports;
        this.currentActor = currentActor;
    }

    @GetMapping("/overview")
    public ClassOverview overview(@PathVariable @Min(1) long courseId) {
        return analytics.overview(courseId);
    }

    @GetMapping("/students/{accountId}")
    public StudentReport student(@PathVariable @Min(1) long courseId,
                                 @PathVariable @Min(1) long accountId) {
        return analytics.studentReport(courseId, accountId, true);
    }

    @GetMapping("/me")
    public StudentReport me(@PathVariable @Min(1) long courseId) {
        return analytics.studentReport(courseId, currentActor.require().userId(), false);
    }

    @GetMapping(value = "/export.xlsx",
            produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> export(@PathVariable @Min(1) long courseId) {
        byte[] body = exports.exportXlsx(courseId);
        String filename = java.net.URLEncoder.encode("班级学情-" + courseId + ".xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .body(body);
    }
}
