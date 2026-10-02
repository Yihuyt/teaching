package cn.utcy.teaching.courseware.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 课件对象存储的孤儿清理器(对账兜底)。删除路径只入队**从行导出**的键(文档引用、素材包图片清单),
 * 行导不出来的对象会留在 bucket 里:未放上页面的生成图 / 素材图拷贝、素材原件(sources/)、
 * TTS 合成后因文本已改而没回填的音频、崩溃时已删行未入队的残余。
 * 定时全量遍历 courseware/ 前缀:归属已不存在的课件 / 素材包的对象直接入队删除;
 * 归属仍在的,只删「未被引用且已存在超过宽限期」的——宽限期保护"刚生成、马上要放上页面"的图。
 * 同时删除超龄仍处 parsing 的素材包行(进程崩溃的半成品)。
 */
@Component
class CoursewareAssetJanitor {

    private static final Logger log = LoggerFactory.getLogger(CoursewareAssetJanitor.class);
    /** 未引用对象的宽限期:覆盖一次最长的工作台会话(生成图到放上页面之间的窗口) */
    static final Duration ORPHAN_GRACE = Duration.ofHours(24);
    private static final Duration SWEEP_INTERVAL = Duration.ofHours(6);

    private final CoursewareAssetStorage storage;
    private final CoursewareMapper coursewares;
    private final MaterialBundleMapper bundles;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    CoursewareAssetJanitor(CoursewareAssetStorage storage, CoursewareMapper coursewares,
                           MaterialBundleMapper bundles, ObjectMapper objectMapper,
                           TransactionTemplate transactions) {
        this.storage = storage;
        this.coursewares = coursewares;
        this.bundles = bundles;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
    }

    @Scheduled(fixedDelayString = "PT6H", initialDelayString = "PT10M")
    public void sweep() {
        try {
            int removedRows = removeStaleParsingBundles();
            List<String> orphans = findOrphans(storage.listAll());
            if (!orphans.isEmpty()) {
                transactions.executeWithoutResult(status -> storage.enqueueDelete(orphans));
            }
            if (removedRows > 0 || !orphans.isEmpty()) {
                log.info("课件对象对账:回收孤儿对象 {} 个,清理崩溃遗留素材包行 {} 条", orphans.size(), removedRows);
            }
        } catch (RuntimeException exception) {
            log.warn("课件对象对账失败,下轮重试", exception);
        }
    }

    /** 超过宽限期仍在 parsing 的素材包行 = 进程崩溃的半成品;删行,其对象随即成为死前缀被回收 */
    private int removeStaleParsingBundles() {
        LocalDateTime staleBefore = LocalDateTime.now(ZoneOffset.UTC)
                .minusSeconds(ORPHAN_GRACE.toSeconds());
        Integer removed = transactions.execute(status -> bundles.delete(
                new LambdaQueryWrapper<MaterialBundleEntity>()
                        .eq(MaterialBundleEntity::getState, MaterialBundleEntity.STATE_PARSING)
                        .lt(MaterialBundleEntity::getCreatedAt, staleBefore)));
        return removed == null ? 0 : removed;
    }

    List<String> findOrphans(List<CoursewareAssetStorage.StoredObject> objects) {
        Set<Long> liveCoursewares = new HashSet<>();
        coursewares.selectList(new LambdaQueryWrapper<CoursewareEntity>().select(CoursewareEntity::getId))
                .forEach(row -> liveCoursewares.add(row.getId()));
        Set<Long> liveBundles = new HashSet<>();
        Set<String> referenced = new HashSet<>();
        for (MaterialBundleEntity bundle : bundles.selectList(new LambdaQueryWrapper<MaterialBundleEntity>()
                .select(MaterialBundleEntity::getId, MaterialBundleEntity::getImagesJson))) {
            liveBundles.add(bundle.getId());
            CoursewareRowPurger.collectBundleImageKeys(bundle, objectMapper, referenced);
        }
        Map<Long, Set<String>> stageRefs = new HashMap<>();
        Date graceBefore = new Date(System.currentTimeMillis() - ORPHAN_GRACE.toMillis());

        List<String> orphans = new java.util.ArrayList<>();
        for (CoursewareAssetStorage.StoredObject object : objects) {
            String key = object.key();
            Long bundleId = bundleIdOf(key);
            if (bundleId != null) {
                if (!liveBundles.contains(bundleId)) {
                    orphans.add(key);
                } else if (!key.contains("/sources/") && !referenced.contains(key)
                        && object.lastModified().before(graceBefore)) {
                    orphans.add(key); // 解析出但没进清单的图(不该发生,兜底)
                }
                continue;
            }
            Long coursewareId = coursewareIdOf(key);
            if (coursewareId == null) {
                continue; // 不认识的键形状,不动
            }
            if (!liveCoursewares.contains(coursewareId)) {
                orphans.add(key);
                continue;
            }
            if (!stageRefs.containsKey(coursewareId)) {
                stageRefs.put(coursewareId, referencedByStage(coursewareId));
            }
            Set<String> refs = stageRefs.get(coursewareId);
            if (refs != null && !refs.contains(key) && object.lastModified().before(graceBefore)) {
                orphans.add(key);
            }
        }
        return orphans;
    }

    /** 课件文档引用的全部对象键;文档损坏时返回 null(该课件本轮全保留) */
    private Set<String> referencedByStage(long coursewareId) {
        CoursewareEntity entity = coursewares.selectById(coursewareId);
        if (entity == null) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        try {
            collect(objectMapper.readTree(entity.getBody()), keys);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            log.warn("课件 {} 文档损坏,本轮不回收其对象", coursewareId, exception);
            return null;
        }
        return keys;
    }

    private static void collect(JsonNode node, Set<String> out) {
        if (node.isObject()) {
            String audio = node.path("audioPath").asText("");
            if (!audio.isBlank()) {
                out.add(audio);
            }
            if ("image".equals(node.path("type").asText()) && !node.path("src").asText("").isBlank()) {
                out.add(node.path("src").asText());
            }
            String video = node.path("video").path("src").asText("");
            if (!video.isBlank()) {
                out.add(video);
            }
            node.forEach(child -> collect(child, out));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, out));
        }
    }

    static Long bundleIdOf(String key) {
        if (!key.startsWith("courseware/bundles/")) {
            return null;
        }
        return idSegment(key.substring("courseware/bundles/".length()));
    }

    static Long coursewareIdOf(String key) {
        if (!key.startsWith("courseware/") || key.startsWith("courseware/bundles/")) {
            return null;
        }
        return idSegment(key.substring("courseware/".length()));
    }

    private static Long idSegment(String rest) {
        int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        try {
            return Long.parseLong(rest.substring(0, slash));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
