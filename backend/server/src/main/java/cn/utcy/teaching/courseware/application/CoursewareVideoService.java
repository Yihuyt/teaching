package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;

/**
 * 视频页的视频:教师上传本地 mp4 / webm,按字节内容识别格式(不信文件名与声明的 MIME),
 * 落课件名下的存储并返回可放进视频页的对象键与短期播放地址。放上哪一页由随后的编辑操作(add_video_scene / set_video)决定。
 */
@Service
public class CoursewareVideoService {

    static final long MAX_UPLOAD_BYTES = 200L * 1024 * 1024;

    private static final byte[] WEBM_MAGIC = {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};

    private final CoursewareAssetStorage assets;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;

    public CoursewareVideoService(CoursewareAssetStorage assets, CourseAccess courseAccess, CurrentActor currentActor) {
        this.assets = assets;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
    }

    public record Uploaded(String src, String url) {
    }

    public Uploaded upload(long courseId, long coursewareId, MultipartFile file) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("请选择一个视频文件");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BadRequestException("视频不能超过 " + (MAX_UPLOAD_BYTES / 1024 / 1024) + "MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("视频读取失败,请重新选择");
        }
        String[] format = probe(bytes);
        if (format == null) {
            throw new BadRequestException("只支持 MP4 与 WebM 视频");
        }
        String key = assets.putVideo(coursewareId, "upload-" + Ids.random(10) + "." + format[1], bytes, format[0]);
        return new Uploaded(key, assets.presignGet(key).toString());
    }

    /** 按文件头识别:MP4 系列(ISO BMFF,偏移 4 处是 "ftyp")或 WebM(EBML 头);返回 {contentType, 扩展名} */
    static String[] probe(byte[] bytes) {
        if (bytes.length >= 12 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
            return new String[]{"video/mp4", "mp4"};
        }
        if (bytes.length >= 4 && Arrays.equals(Arrays.copyOf(bytes, 4), WEBM_MAGIC)) {
            return new String[]{"video/webm", "webm"};
        }
        return null;
    }
}
