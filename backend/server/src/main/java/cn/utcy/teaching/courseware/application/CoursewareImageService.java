package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.ai.media.QwenImageClient;
import cn.utcy.teaching.ai.media.QwenImageClient.GeneratedImage;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.SceneBrief;
import cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage;
import cn.utcy.teaching.courseware.infrastructure.CoursewareProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

@Service
public class CoursewareImageService {

    static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    /** 能识别并读出像素尺寸的格式(ImageIO 标准读取器) → 存储的内容类型与扩展名 */
    private static final Map<String, String[]> UPLOAD_FORMATS = Map.of(
            "png", new String[]{"image/png", "png"},
            "jpeg", new String[]{"image/jpeg", "jpg"},
            "gif", new String[]{"image/gif", "gif"});

    /** 宽高比 → 模型支持的像素尺寸(qwen-image 系列的标准档位) */
    static final Map<String, String> SIZES = Map.of(
            "16:9", "1664*928",
            "4:3", "1472*1104",
            "1:1", "1328*1328",
            "3:4", "1104*1472");

    private final QwenImageClient client;
    private final CoursewareAssetStorage assets;
    private final CoursewareProperties properties;
    private final CoursewareMaterialBundleService bundles;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final CourseAiKeys aiKeys;

    public CoursewareImageService(QwenImageClient client, CoursewareAssetStorage assets,
                                  CoursewareProperties properties, CoursewareMaterialBundleService bundles,
                                  CourseAccess courseAccess, CurrentActor currentActor, CourseAiKeys aiKeys) {
        this.client = client;
        this.assets = assets;
        this.properties = properties;
        this.bundles = bundles;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.aiKeys = aiKeys;
    }

    public record Picked(String src, int width, int height, String url) {
    }

    public Picked generate(long courseId, long coursewareId, String prompt, String aspectRatio) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        return generateInternal(aiKeys.llmKeyForCourse(courseId), coursewareId, prompt, aspectRatio);
    }

    public Picked generateInternal(String apiKey, long coursewareId, String prompt, String aspectRatio) {
        String size = SIZES.get(aspectRatio == null || aspectRatio.isBlank()
                ? SceneBrief.Illustration.DEFAULT_ASPECT_RATIO : aspectRatio);
        if (size == null) {
            throw new BadRequestException("aspectRatio 只能是 " + String.join("/", SceneBrief.Illustration.ASPECT_RATIOS));
        }
        GeneratedImage image = client.generate(apiKey, properties.imageModel(), prompt.strip(), null, size);
        String filename = "gen-" + Ids.random(10) + ".png";
        String key = assets.putImage(coursewareId, filename, image.bytes(), image.contentType());
        return new Picked(key, image.width(), image.height(), assets.presignGet(key).toString());
    }

    public Block.Image adoptMaterialImage(long coursewareId, CoursewareMaterialBundleService.BundleImage image,
                                          String blockId, String caption) {
        String filename = image.objectKey().substring(image.objectKey().lastIndexOf('/') + 1);
        String key = assets.copyToCourseware(image.objectKey(), coursewareId, filename);
        return new Block.Image(blockId, key, image.width(), image.height(), caption);
    }

    public Picked pickMaterialImage(long courseId, long coursewareId, long materialId, String imageId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        CoursewareMaterialBundleService.Bundle bundle = bundles.load(courseId, coursewareId, materialId);
        CoursewareMaterialBundleService.BundleImage image = bundle.imagesById().get(imageId);
        if (image == null) {
            throw new BadRequestException("素材里没有这张图片");
        }
        Block.Image adopted = adoptMaterialImage(coursewareId, image, "blk-image-pending", null);
        return new Picked(adopted.src(), adopted.width(), adopted.height(), assets.presignGet(adopted.src()).toString());
    }

    /**
     * 教师上传本地图片:按字节内容识别格式(不信文件名与声明的 MIME),只收 png / jpeg / gif,
     * 读出像素尺寸后落课件名下的存储。
     */
    public Picked upload(long courseId, long coursewareId, MultipartFile file) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("请选择一张图片");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new BadRequestException("图片不能超过 " + (MAX_UPLOAD_BYTES / 1024 / 1024) + "MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BadRequestException("图片读取失败,请重新选择");
        }
        Probe probe = probe(bytes);
        if (probe == null) {
            throw new BadRequestException("只支持 PNG、JPEG、GIF 图片");
        }
        String[] format = UPLOAD_FORMATS.get(probe.format());
        String filename = "upload-" + Ids.random(10) + "." + format[1];
        String key = assets.putImage(coursewareId, filename, bytes, format[0]);
        return new Picked(key, probe.width(), probe.height(), assets.presignGet(key).toString());
    }

    private record Probe(String format, int width, int height) {
    }

    private static Probe probe(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!UPLOAD_FORMATS.containsKey(format)) {
                    return null;
                }
                reader.setInput(in);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    return null;
                }
                return new Probe(format, width, height);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            return null;
        }
    }
}
