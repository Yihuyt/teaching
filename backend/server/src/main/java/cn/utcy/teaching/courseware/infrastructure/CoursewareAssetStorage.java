package cn.utcy.teaching.courseware.infrastructure;

import cn.utcy.teaching.shared.storage.ObjectStorageDeletionQueue;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.ObjectListing;
import com.aliyun.oss.model.ObjectMetadata;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Collection;
import java.util.Set;

/**
 * 课件对象存储(课程资料 bucket,键前缀 courseware/):讲稿音频 `courseware/{id}/audio/`、
 * 幻灯片图片 `courseware/{id}/images/`(素材图片拷贝与文生图产物)、视频页的视频 `courseware/{id}/videos/`、素材包 `courseware/bundles/{bundleId}/`
 * (教师上传的原件 `sources/` 与解析出的图片;课件自有,与课程资料库无关)。
 * 键原样存进课件文档(audioPath / image.src);播放/编辑视图另给 对象键 → 短期预签名 URL 的映射。
 * 删除一律走平台统一的异步删除队列。
 */
@Component
public class CoursewareAssetStorage {
    /** 一堂课的播放时长上限量级:预签名有效期取 2 小时 */
    private static final Duration PLAYBACK_URL_LIFETIME = Duration.ofHours(2);

    private final OSS oss;
    private final CoursewareOssProperties bucket;
    private final ObjectStorageDeletionQueue deletionQueue;

    public CoursewareAssetStorage(OSS oss, CoursewareOssProperties bucket, ObjectStorageDeletionQueue deletionQueue) {
        this.oss = oss;
        this.bucket = bucket;
        this.deletionQueue = deletionQueue;
    }

    public String objectKey(long coursewareId, String filename) {
        return "courseware/" + coursewareId + "/audio/" + filename;
    }

    /**
     * 键是否归属该课件:课件文档只允许引用自己前缀下的对象。
     * 这是对象归属的唯一裁决点——写入校验、预签名、精确释放都以它为准,
     * 否则一个伪造的对象键就能读到(乃至删除队列里删掉)同 bucket 里别的课程的文件。
     */
    public static boolean isOwnedKey(long coursewareId, String key) {
        return key != null && key.startsWith(coursewarePrefix(coursewareId));
    }

    private static String coursewarePrefix(long coursewareId) {
        return "courseware/" + coursewareId + "/";
    }

    public static String bundlePrefix(long bundleId) {
        return "courseware/bundles/" + bundleId + "/";
    }

    public String put(long coursewareId, String filename, byte[] audio, String contentType) {
        return putObject(objectKey(coursewareId, filename), audio, contentType);
    }

    public String putImage(long coursewareId, String filename, byte[] bytes, String contentType) {
        return putObject(coursewarePrefix(coursewareId) + "images/" + filename, bytes, contentType);
    }

    public String putVideo(long coursewareId, String filename, byte[] bytes, String contentType) {
        return putObject(coursewarePrefix(coursewareId) + "videos/" + filename, bytes, contentType);
    }

    public String putBundleSource(long bundleId, int order, String suffix, byte[] bytes, String contentType) {
        return putObject(bundlePrefix(bundleId) + "sources/" + order + "." + suffix, bytes, contentType);
    }

    public String putBundleImage(long bundleId, String filename, byte[] bytes, String contentType) {
        return putObject(bundlePrefix(bundleId) + filename, bytes, contentType);
    }

    public String copyToCourseware(String sourceKey, long coursewareId, String filename) {
        String target = coursewarePrefix(coursewareId) + "images/" + filename;
        oss.copyObject(bucket.bucket(), sourceKey, bucket.bucket(), target);
        return target;
    }

    public byte[] get(String objectKey) {
        try (com.aliyun.oss.model.OSSObject object = oss.getObject(bucket.bucket(), objectKey);
             java.io.InputStream in = object.getObjectContent()) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("读取课件对象失败:" + objectKey, e);
        }
    }

    private String putObject(String key, byte[] bytes, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(bytes.length);
        metadata.setContentType(contentType);
        oss.putObject(bucket.bucket(), key, new ByteArrayInputStream(bytes), metadata);
        return key;
    }

    public URL presignGet(String objectKey) {
        GeneratePresignedUrlRequest request =
                new GeneratePresignedUrlRequest(bucket.bucket(), objectKey, HttpMethod.GET);
        request.setExpiration(new Date(System.currentTimeMillis() + PLAYBACK_URL_LIFETIME.toMillis()));
        return oss.generatePresignedUrl(request);
    }

    /**
     * 精确释放一批对象(课件写入时"旧引用 − 新引用"得到的音频 / 图片):与业务写入同事务入队(MANDATORY 传播)。
     * 刻意不按前缀清扫——刚生成、尚未放上页面的配图本来就是未引用的,按前缀清扫会把它删掉。
     */
    public void enqueueDelete(Collection<String> keys) {
        for (String key : keys) {
            deletionQueue.enqueue(bucket.bucket(), key);
        }
    }

    public record StoredObject(String key, Date lastModified) {
    }

    /**
     * 遍历 courseware/ 前缀下的全部对象——**只供孤儿清理器在事务外调用**。
     * 删除路径的键一律从数据库行导出(文档引用 / 素材包图片清单),不在事务里做网络遍历;
     * 行导不出来的(未放上页面的生成图、素材原件)由清理器按年龄回收。
     */
    public List<StoredObject> listAll() {
        List<StoredObject> objects = new java.util.ArrayList<>();
        String marker = null;
        do {
            ObjectListing listing = oss.listObjects(
                    new com.aliyun.oss.model.ListObjectsRequest(bucket.bucket())
                            .withPrefix("courseware/")
                            .withMarker(marker));
            for (OSSObjectSummary summary : listing.getObjectSummaries()) {
                objects.add(new StoredObject(summary.getKey(), summary.getLastModified()));
            }
            marker = listing.getNextMarker();
        } while (marker != null);
        return objects;
    }
}
