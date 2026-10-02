package cn.utcy.teaching.courseware.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 课件及其从属行(浏览、问答、测验作答、素材包)的显式删除(数据库不设外键);
 * 删课件 / 删课程共用,必须在业务事务内调用。
 * OSS 对象的键从数据库行导出(文档引用 + 素材包图片清单)并与行删除同事务入队——事务里不做网络遍历;
 * 行导不出来的(未放上页面的生成图、素材原件)由孤儿清理器按前缀回收。
 */
@Component
public class CoursewareRowPurger {

    private final CoursewareMapper coursewares;
    private final SceneViewEventMapper sceneViews;
    private final QaEventMapper qaEvents;
    private final QuizAttemptMapper quizAttempts;
    private final MaterialBundleMapper bundles;
    private final CoursewareAssetStorage assetStorage;
    private final ObjectMapper objectMapper;

    CoursewareRowPurger(CoursewareMapper coursewares, SceneViewEventMapper sceneViews,
                        QaEventMapper qaEvents, QuizAttemptMapper quizAttempts, MaterialBundleMapper bundles, CoursewareAssetStorage assetStorage, ObjectMapper objectMapper) {
        this.coursewares = coursewares;
        this.sceneViews = sceneViews;
        this.qaEvents = qaEvents;
        this.quizAttempts = quizAttempts;
        this.bundles = bundles;
        this.assetStorage = assetStorage;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeCoursewares(List<Long> coursewareIds) {
        if (coursewareIds.isEmpty()) {
            return;
        }
        enqueueRowDerivedAssets(coursewareIds);
        bundles.delete(new LambdaQueryWrapper<MaterialBundleEntity>()
                .in(MaterialBundleEntity::getCoursewareId, coursewareIds));
        sceneViews.delete(new LambdaQueryWrapper<SceneViewEventEntity>()
                .in(SceneViewEventEntity::getCoursewareId, coursewareIds));
        qaEvents.delete(new LambdaQueryWrapper<QaEventEntity>()
                .in(QaEventEntity::getCoursewareId, coursewareIds));
        quizAttempts.delete(new LambdaQueryWrapper<QuizAttemptEntity>()
                .in(QuizAttemptEntity::getCoursewareId, coursewareIds));
        coursewares.delete(new LambdaQueryWrapper<CoursewareEntity>()
                .in(CoursewareEntity::getId, coursewareIds));
    }

    private void enqueueRowDerivedAssets(List<Long> coursewareIds) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        for (CoursewareEntity entity : coursewares.selectList(new LambdaQueryWrapper<CoursewareEntity>()
                .in(CoursewareEntity::getId, coursewareIds))) {
            try {
                JsonNode body = objectMapper.readTree(entity.getBody());
                collectStageKeys(body, keys);
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                // 文档损坏:行照删,对象由孤儿清理器按前缀回收
            }
        }
        for (MaterialBundleEntity bundle : bundles.selectList(new LambdaQueryWrapper<MaterialBundleEntity>()
                .in(MaterialBundleEntity::getCoursewareId, coursewareIds))) {
            collectBundleImageKeys(bundle, objectMapper, keys);
        }
        if (!keys.isEmpty()) {
            assetStorage.enqueueDelete(keys);
        }
    }

    public static void collectBundleImageKeys(MaterialBundleEntity bundle, ObjectMapper objectMapper,
                                       java.util.Set<String> out) {
        try {
            for (JsonNode image : objectMapper.readTree(bundle.getImagesJson())) {
                String key = image.path("objectKey").asText("");
                if (!key.isBlank()) {
                    out.add(key);
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            // 清单损坏:图片由孤儿清理器回收
        }
    }

    /** 遍历文档 JSON 收集 audioPath、image.src 与 video.src(不重建领域对象,损坏文档也尽力收集) */
    private static void collectStageKeys(JsonNode node, java.util.Set<String> out) {
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
            node.forEach(child -> collectStageKeys(child, out));
        } else if (node.isArray()) {
            node.forEach(child -> collectStageKeys(child, out));
        }
    }
}
