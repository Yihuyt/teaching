package cn.utcy.teaching.courseware.api;

import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.application.CoursewareMaterialBundleService;
import cn.utcy.teaching.courseware.application.CoursewareMaterialBundleService.Bundle;
import cn.utcy.teaching.courseware.application.CoursewareMaterialBundleService.BundleImage;
import cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 课件工作台的素材(教师管理面):上传 ≤5 份文件解析合并为一个素材包挂在课件名下,作大纲的内容依据与配图来源;
 * 原件进课件自有存储区,不经课程资料库;密钥只在服务端流转。
 */
@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/coursewares/{coursewareId}/materials")
public class CoursewareMaterialController {

    private final CoursewareMaterialBundleService bundles;
    private final CoursewareAssetStorage assets;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;

    public CoursewareMaterialController(CoursewareMaterialBundleService bundles, CoursewareAssetStorage assets,
                                        CourseAccess courseAccess, CurrentActor currentActor) {
        this.bundles = bundles;
        this.assets = assets;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
    }

    /** url 为短期预览地址(教师选图时看缩略图用) */
    public record CoursewareMaterialImageView(String id, String sourceDocumentName, int pageNumber, int width, int height,
                                    String description, String url) {
    }

    public record CoursewareMaterialView(long id, String name, int chars, int imageCount, List<CoursewareMaterialImageView> images,
                               long createdAt) {
    }

    @GetMapping
    public List<CoursewareMaterialView> list(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long coursewareId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        return bundles.listForCourseware(courseId, coursewareId).stream().map(this::view).toList();
    }

    /**
     * 上传并解析合并为素材包(SSE;files 的顺序即合并顺序与 img 编号顺序):progress{message} →
     * done{bundleId, chars, truncated, totalRawChars, imageCount, visionImageCount, images[]} / error
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
            schema = @Schema(type = "string")))
    public SseEmitter parse(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long coursewareId,
                            @RequestPart("files") List<MultipartFile> files) {
        return bundles.parseSse(courseId, coursewareId, files);
    }

    @DeleteMapping("/{bundleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long coursewareId,
                       @PathVariable @Min(1) long bundleId) {
        bundles.delete(courseId, coursewareId, bundleId);
    }

    private CoursewareMaterialView view(Bundle bundle) {
        List<CoursewareMaterialImageView> images = bundle.images().stream()
                .map(image -> new CoursewareMaterialImageView(image.id(), image.sourceDocumentName(), image.pageNumber(),
                        image.width(), image.height(), image.description(),
                        assets.presignGet(image.objectKey()).toString()))
                .toList();
        return new CoursewareMaterialView(bundle.id(), bundle.name(), bundle.text().length(), images.size(), images,
                bundle.createdAt());
    }
}
