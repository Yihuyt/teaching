package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.course.application.CourseAiKeys;
import cn.utcy.teaching.shared.util.Text;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.ai.document.DocumentParser;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.utcy.teaching.ai.document.MineruClient;
import cn.utcy.teaching.shared.sse.SseSupport;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage;
import cn.utcy.teaching.courseware.infrastructure.MaterialBundleEntity;
import cn.utcy.teaching.courseware.infrastructure.MaterialBundleMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 课件素材包:教师在工作台上传 ≤5 份文件 → 原件存课件自有的 OSS 区
 * (与课程资料库无关,不进资料库)→ 逐份解析(PDF/Office/图片走 MinerU 带图解析,txt/md 直读)
 * → {@link MaterialBundleBuilder} 合并 → 图片与文本、图片清单落库,挂在课件名下:大纲以其文本为依据,图片可挑给讲解页或由教师手工插入。
 * 文件校验、原件上传、MinerU 令牌在请求线程完成(4xx 不进流;multipart 临时文件随请求结束而销毁);解析本身在 SSE 任务里做。
 * 解析失败或取消时素材包连同原件一并丢弃;教师可在工作台移除;课件删除时随之删除。
 */
@Service
public class CoursewareMaterialBundleService {

    private static final Logger log = LoggerFactory.getLogger(CoursewareMaterialBundleService.class);

    public static final int MAX_FILES = 5;
    static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 150L * 1024 * 1024;
    /** MinerU 云端支持的类型 + 纯文本 */
    private static final Map<String, String> MIME_BY_SUFFIX = Map.ofEntries(
            Map.entry("pdf", "application/pdf"),
            Map.entry("doc", "application/msword"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("ppt", "application/vnd.ms-powerpoint"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("gif", "image/gif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("jp2", "image/jp2"),
            Map.entry("txt", "text/plain"),
            Map.entry("md", "text/markdown"),
            Map.entry("markdown", "text/markdown"));
    private static final Set<String> TEXT_SUFFIXES = Set.of("txt", "md", "markdown");

    private final CoursewareApplicationService coursewares;
    private final MineruClient mineru;
    private final DocumentParser textParser;
    private final CourseAiKeys aiKeys;
    private final CoursewareAssetStorage assets;
    private final MaterialBundleMapper bundles;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final ObjectMapper objectMapper;
    private final TaskExecutor sseExecutor;
    private final TransactionTemplate transactions;

    public CoursewareMaterialBundleService(CoursewareApplicationService coursewares, MineruClient mineru,
                                           DocumentParser textParser, CourseAiKeys aiKeys,
                                           CoursewareAssetStorage assets, MaterialBundleMapper bundles,
                                           CourseAccess courseAccess, CurrentActor currentActor,
                                           ObjectMapper objectMapper,
                                           @Qualifier("sseTaskExecutor") TaskExecutor sseExecutor,
                                           TransactionTemplate transactions) {
        this.coursewares = coursewares;
        this.mineru = mineru;
        this.textParser = textParser;
        this.aiKeys = aiKeys;
        this.assets = assets;
        this.bundles = bundles;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
        this.sseExecutor = sseExecutor;
        this.transactions = transactions;
    }

    public record BundleImage(String id, String objectKey, String contentType, int width, int height,
                              int pageNumber, String description, String sourceDocumentName,
                              int sourceDocumentOrder, int visionPriority) {
    }

    public record BundleSource(String name, int order, String mimeType, int pageCount) {
    }

    public record Bundle(long id, long courseId, long coursewareId, String name, String text, List<BundleImage> images,
                         List<BundleSource> sources, long createdAt) {
        public Map<String, BundleImage> imagesById() {
            Map<String, BundleImage> byId = new LinkedHashMap<>();
            images.forEach(image -> byId.put(image.id(), image));
            return byId;
        }
    }

    record Resolved(String name, int order, String suffix, String mimeType, String fileUrl, String mineruToken) {
    }

    /**
     * 上传、解析并合并(SSE):progress{message} → done{bundleId, chars, truncated, imageCount,
     * visionImageCount, images:[{id, sourceDocumentName, pageNumber, width, height, description, url}]}。
     */
    public SseEmitter parseSse(long courseId, long coursewareId, List<MultipartFile> files) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        coursewares.requireEntity(courseId, coursewareId);
        long accountId = currentActor.require().userId();
        if (files.isEmpty()) {
            throw new BadRequestException("请至少上传一份文件");
        }
        if (files.size() > MAX_FILES) {
            throw new BadRequestException("一次最多附加 " + MAX_FILES + " 份文件");
        }
        validate(files);
        String mineruToken = files.stream().anyMatch(file -> !TEXT_SUFFIXES.contains(suffixOf(filename(file))))
                ? aiKeys.mineruTokenForCourse(courseId)
                : null;
        String name = files.stream().map(CoursewareMaterialBundleService::filename)
                .reduce((a, b) -> a + "、" + b).orElse("素材");
        long bundleId = transactions.execute(status -> createBundle(courseId, coursewareId, accountId, name));
        List<Resolved> resolved;
        try {
            resolved = stage(bundleId, files, mineruToken);
        } catch (RuntimeException exception) {
            transactions.executeWithoutResult(status -> discard(bundleId));
            throw exception;
        }
        return SseSupport.run(sseExecutor, objectMapper, sink -> {
            boolean completed = false;
            try {
                List<MaterialBundleBuilder.Part> parts = new ArrayList<>();
                for (Resolved item : resolved) {
                    if (sink.cancelled()) {
                        return;
                    }
                    sink.emit(Map.of("type", "progress", "message",
                            "正在解析「" + item.name() + "」(" + item.order() + "/" + resolved.size() + ")…"));
                    parts.add(parse(item, message -> sink.emit(Map.of("type", "progress",
                            "message", "「" + item.name() + "」" + message)), sink::cancelled));
                }
                if (sink.cancelled()) {
                    return;
                }
                MaterialBundleBuilder.Result result = MaterialBundleBuilder.build(parts,
                        MaterialBundleBuilder.MAX_CHARS, MaterialBundleBuilder.MAX_VISION_IMAGES);
                sink.emit(Map.of("type", "progress", "message", "合并完成,正在保存图片…"));
                Bundle bundle = persist(bundleId, parts, result);
                List<Map<String, Object>> imageViews = new ArrayList<>();
                for (BundleImage image : bundle.images()) {
                    Map<String, Object> view = new HashMap<>();
                    view.put("id", image.id());
                    view.put("sourceDocumentName", image.sourceDocumentName());
                    view.put("pageNumber", image.pageNumber());
                    view.put("width", image.width());
                    view.put("height", image.height());
                    view.put("description", image.description());
                    view.put("visionPriority", image.visionPriority());
                    view.put("url", assets.presignGet(image.objectKey()).toString());
                    imageViews.add(view);
                }
                Map<String, Object> done = new HashMap<>();
                done.put("type", "done");
                done.put("bundleId", bundle.id());
                done.put("chars", bundle.text().length());
                done.put("truncated", result.totalRawTextLength() > result.textContentBudget());
                done.put("totalRawChars", result.totalRawTextLength());
                done.put("imageCount", result.totalImageCount());
                done.put("visionImageCount", result.visionImageCount());
                done.put("images", imageViews);
                sink.emit(done);
                completed = true;
            } finally {
                if (!completed) {
                    transactions.executeWithoutResult(status -> discard(bundleId));
                }
            }
        });
    }

    /**
     * 课件名下的全部就绪素材包(工作台清单与生成流水线)。
     * 无权限检查:调用方已在入口完成授权(生成任务跑在后台线程,没有请求上下文)。
     */
    @Transactional(readOnly = true)
    public List<Bundle> listForCourseware(long courseId, long coursewareId) {
        return bundles.selectList(new LambdaQueryWrapper<MaterialBundleEntity>()
                        .eq(MaterialBundleEntity::getCourseId, courseId)
                        .eq(MaterialBundleEntity::getCoursewareId, coursewareId)
                        .eq(MaterialBundleEntity::getState, MaterialBundleEntity.STATE_READY)
                        .orderByAsc(MaterialBundleEntity::getId)).stream()
                .map(this::toBundle)
                .toList();
    }

    /**
     * 读取素材包;不属于该课件或尚未就绪按 404。
     * 无权限检查:调用方已在入口完成授权。
     */
    @Transactional(readOnly = true)
    public Bundle load(long courseId, long coursewareId, long bundleId) {
        MaterialBundleEntity entity = bundles.selectById(bundleId);
        if (entity == null || entity.getCourseId() != courseId || entity.getCoursewareId() != coursewareId
                || !MaterialBundleEntity.STATE_READY.equals(entity.getState())) {
            throw new NotFoundException("素材不存在");
        }
        return toBundle(entity);
    }

    @Transactional
    public void delete(long courseId, long coursewareId, long bundleId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        load(courseId, coursewareId, bundleId);
        discard(bundleId);
    }

    /** 须在事务内调用(删除队列入队为 MANDATORY 传播);图片键从行导出,原件由孤儿清理器回收 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void discard(long bundleId) {
        MaterialBundleEntity entity = bundles.selectById(bundleId);
        if (entity == null) {
            return;
        }
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        cn.utcy.teaching.courseware.infrastructure.CoursewareRowPurger
                .collectBundleImageKeys(entity, objectMapper, keys);
        bundles.deleteById(bundleId);
        if (!keys.isEmpty()) {
            assets.enqueueDelete(keys);
        }
    }

    private static void validate(List<MultipartFile> files) {
        long totalBytes = 0;
        for (MultipartFile file : files) {
            String name = filename(file);
            if (MIME_BY_SUFFIX.get(suffixOf(name)) == null) {
                throw new BadRequestException("文件「" + name
                        + "」类型不支持解析,可用类型:pdf、doc/docx、ppt/pptx、xls/xlsx、图片、txt、md");
            }
            if (file.isEmpty()) {
                throw new BadRequestException("文件「" + name + "」是空文件");
            }
            if (file.getSize() > MAX_FILE_BYTES) {
                throw new BadRequestException("文件「" + name + "」超过 50MB 上限");
            }
            totalBytes += file.getSize();
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw new BadRequestException("附加文件总大小超过 150MB 上限");
            }
        }
    }

    private long createBundle(long courseId, long coursewareId, long accountId, String name) {
        MaterialBundleEntity entity = new MaterialBundleEntity(courseId, coursewareId, accountId,
                Text.truncate(name, 255), "", "[]", "[]", LocalDateTime.now(ZoneOffset.UTC));
        bundles.insert(entity);
        return entity.getId();
    }

    private List<Resolved> stage(long bundleId, List<MultipartFile> files, String mineruToken) {
        List<Resolved> resolved = new ArrayList<>();
        int order = 0;
        for (MultipartFile file : files) {
            String name = filename(file);
            String suffix = suffixOf(name);
            String mimeType = MIME_BY_SUFFIX.get(suffix);
            byte[] bytes;
            try {
                bytes = file.getBytes();
            } catch (IOException exception) {
                throw new IllegalStateException("读取上传文件失败:" + name, exception);
            }
            String key = assets.putBundleSource(bundleId, ++order, suffix, bytes, mimeType);
            resolved.add(new Resolved(name, order, suffix, mimeType, assets.presignGet(key).toString(),
                    TEXT_SUFFIXES.contains(suffix) ? null : mineruToken));
        }
        return resolved;
    }

    private MaterialBundleBuilder.Part parse(Resolved item, java.util.function.Consumer<String> onProgress,
                                             java.util.function.BooleanSupplier cancelled) {
        if (TEXT_SUFFIXES.contains(item.suffix())) {
            String text = textParser.parse(item.name(), item.fileUrl(), null, onProgress, cancelled);
            return new MaterialBundleBuilder.Part(item.name(), item.order(), item.mimeType(), 0, text, List.of());
        }
        MineruClient.ParsedDocument parsed = mineru.parseDocument(item.mineruToken(), item.fileUrl(),
                onProgress, cancelled);
        List<MaterialBundleBuilder.Image> images = new ArrayList<>();
        for (MineruClient.ParsedImage image : parsed.images()) {
            // 原始 id 取结果包内路径:Markdown 里的 ![](images/x.jpg) 引用会被合并器改写成 img_N
            images.add(new MaterialBundleBuilder.Image(image.path(), image.bytes(), image.contentType(),
                    image.pageNumber(), image.description(), image.width(), image.height(),
                    item.name(), item.order(), 0));
        }
        return new MaterialBundleBuilder.Part(item.name(), item.order(), item.mimeType(), parsed.pageCount(),
                parsed.markdown(), images);
    }

    private Bundle persist(long bundleId, List<MaterialBundleBuilder.Part> parts,
                           MaterialBundleBuilder.Result result) {
        List<BundleSource> sources = parts.stream()
                .map(part -> new BundleSource(part.name(), part.order(), part.mimeType(), part.pageCount()))
                .toList();
        List<BundleImage> stored = new ArrayList<>();
        for (MaterialBundleBuilder.Image image : result.images()) {
            String filename = image.id() + "." + extensionOf(image.contentType());
            String key = assets.putBundleImage(bundleId, filename, image.bytes(), image.contentType());
            stored.add(new BundleImage(image.id(), key, image.contentType(), image.width(), image.height(),
                    image.pageNumber(), image.description(), image.sourceDocumentName(),
                    image.sourceDocumentOrder(), image.visionPriority()));
        }
        MaterialBundleEntity entity = bundles.selectById(bundleId);
        entity.setText(result.text());
        entity.setImagesJson(toJson(stored));
        entity.setSourcesJson(toJson(sources));
        entity.ready();
        bundles.updateById(entity);
        return toBundle(entity);
    }

    private static String filename(MultipartFile file) {
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new BadRequestException("上传文件缺少文件名");
        }
        // 只取基名:浏览器可能带路径
        String name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1);
        if (name.length() > 255) {
            throw new BadRequestException("文件名不能超过 255 个字符");
        }
        return name;
    }

    private Bundle toBundle(MaterialBundleEntity entity) {
        try {
            List<BundleImage> images = objectMapper.readValue(entity.getImagesJson(),
                    new TypeReference<List<BundleImage>>() { });
            List<BundleSource> sources = objectMapper.readValue(entity.getSourcesJson(),
                    new TypeReference<List<BundleSource>>() { });
            return new Bundle(entity.getId(), entity.getCourseId(), entity.getCoursewareId(), entity.getName(),
                    entity.getText(), images, sources,
                    entity.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("素材包记录损坏:" + entity.getId(), e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String suffixOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot == -1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/bmp" -> "bmp";
            default -> "jpg";
        };
    }
}
