package cn.utcy.teaching.courseware.api;

import cn.utcy.teaching.courseware.application.CoursewareImageService;
import cn.utcy.teaching.courseware.application.CoursewareImageService.Picked;
import cn.utcy.teaching.courseware.domain.SceneBrief;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 教师往课件页面上放图的三个入口:上传本地图片、从课件素材包里选图、按描述文生图。
 * 都只把图落到课件名下的存储并返回 image 块所需的字段;放上哪一页由随后的编辑操作(add_block / replace_block)决定。
 */
@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/coursewares/{coursewareId}/images")
public class CoursewareImageController {

    private final CoursewareImageService images;

    public CoursewareImageController(CoursewareImageService images) {
        this.images = images;
    }

    /** 可放进 image 块的一张图:src 为对象键,width / height 为原图像素尺寸,url 为短期预览地址 */
    public record CoursewareImageView(String src, int width, int height, String url) {
    }

    /** 上传本地图片(png / jpeg / gif,≤10MB) */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CoursewareImageView upload(@PathVariable @Min(1) long courseId,
                                      @PathVariable @Min(1) long coursewareId,
                                      @RequestPart("file") MultipartFile file) {
        return view(images.upload(courseId, coursewareId, file));
    }

    public record PickMaterialImageRequest(@Min(1) long materialId,
                                           @NotBlank(message = "请选择图片") String imageId) {
    }

    @PostMapping("/from-material")
    public CoursewareImageView pickFromMaterial(@PathVariable @Min(1) long courseId,
                                                @PathVariable @Min(1) long coursewareId,
                                                @Valid @RequestBody PickMaterialImageRequest request) {
        return view(images.pickMaterialImage(courseId, coursewareId, request.materialId(), request.imageId()));
    }

    public record GenerateImageRequest(@NotBlank(message = "请描述要生成的图")
                                       @Size(max = SceneBrief.Illustration.MAX_PROMPT_CHARS) String prompt,
                                       @Pattern(regexp = "16:9|4:3|1:1|3:4") String aspectRatio) {
    }

    @PostMapping("/generate")
    public CoursewareImageView generate(@PathVariable @Min(1) long courseId,
                                        @PathVariable @Min(1) long coursewareId,
                                        @Valid @RequestBody GenerateImageRequest request) {
        return view(images.generate(courseId, coursewareId, request.prompt(), request.aspectRatio()));
    }

    private static CoursewareImageView view(Picked picked) {
        return new CoursewareImageView(picked.src(), picked.width(), picked.height(), picked.url());
    }
}
