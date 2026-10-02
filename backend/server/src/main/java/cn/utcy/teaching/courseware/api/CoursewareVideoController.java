package cn.utcy.teaching.courseware.api;

import cn.utcy.teaching.courseware.application.CoursewareVideoService;
import cn.utcy.teaching.courseware.application.CoursewareVideoService.Uploaded;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 视频页的视频上传(教师管理面):只把视频落到课件名下的存储并返回对象键;
 * 建视频页或换视频由随后的编辑操作(add_video_scene / set_video)完成。
 */
@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/coursewares/{coursewareId}/videos")
public class CoursewareVideoController {

    private final CoursewareVideoService videos;

    public CoursewareVideoController(CoursewareVideoService videos) {
        this.videos = videos;
    }

    public record CoursewareVideoView(String src, String url) {
    }

    /** 上传本地视频(mp4 / webm,≤200MB) */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CoursewareVideoView upload(@PathVariable @Min(1) long courseId,
                                      @PathVariable @Min(1) long coursewareId,
                                      @RequestPart("file") MultipartFile file) {
        Uploaded uploaded = videos.upload(courseId, coursewareId, file);
        return new CoursewareVideoView(uploaded.src(), uploaded.url());
    }
}
