package cn.utcy.teaching.courseware.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.courseware.domain.Block;
import cn.utcy.teaching.courseware.domain.Stage;
import cn.utcy.teaching.courseware.domain.StageProjections;
import cn.utcy.teaching.courseware.domain.StageValidator;
import cn.utcy.teaching.courseware.infrastructure.CoursewareAssetStorage;
import cn.utcy.teaching.courseware.infrastructure.CoursewareEntity;
import cn.utcy.teaching.courseware.infrastructure.CoursewareMapper;
import cn.utcy.teaching.courseware.infrastructure.CoursewareRowPurger;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CoursewareApplicationService {

    private final CoursewareMapper coursewareMapper;
    private final StageJsonCodec codec;
    private final CoursewareAssetStorage assetStorage;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final CoursewareRowPurger purger;

    public CoursewareApplicationService(CoursewareMapper coursewareMapper, StageJsonCodec codec,
                                        CoursewareAssetStorage assetStorage, CourseAccess courseAccess,
                                        CurrentActor currentActor, CoursewareRowPurger purger) {
        this.purger = purger;
        this.coursewareMapper = coursewareMapper;
        this.codec = codec;
        this.assetStorage = assetStorage;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
    }

    public record CoursewareSummary(long id, String title, int sceneCount, boolean published,
                                    long createdAt, long updatedAt) {
    }

    /** stage 为完整课件文档 JSON(含测验答案,仅管理面);assetUrls 为文档里对象键 → 短期预签名地址 */
    public record CoursewareDetail(long id, String title, boolean published, long version,
                                   JsonNode stage, Map<String, String> assetUrls, long createdAt, long updatedAt) {
    }

    /** stage 已剥除测验答案与讲解;判分与作答记录走服务端接口 */
    public record CoursewarePlayView(long id, String title, JsonNode stage,
                                     Map<String, String> assetUrls) {
    }

    public record VersionedStage(Stage stage, long version) {
    }

    @Transactional(readOnly = true)
    public List<CoursewareSummary> list(long courseId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        return listByCourse(courseId, false);
    }

    @Transactional(readOnly = true)
    public List<CoursewareSummary> listForLearning(long courseId) {
        courseAccess.requireLearningAccess(courseId, currentActor.require());
        return listByCourse(courseId, true);
    }

    private List<CoursewareSummary> listByCourse(long courseId, boolean publishedOnly) {
        return coursewareMapper.selectList(new LambdaQueryWrapper<CoursewareEntity>()
                        .eq(CoursewareEntity::getCourseId, courseId)
                        .eq(publishedOnly, CoursewareEntity::isPublished, true)
                        .orderByDesc(CoursewareEntity::getUpdatedAt)).stream()
                .map(this::summary)
                .toList();
    }

    private CoursewareSummary summary(CoursewareEntity entity) {
        return new CoursewareSummary(entity.getId(), entity.getTitle(),
                entity.getSceneCount(), entity.isPublished(),
                toEpochMilli(entity.getCreatedAt()), toEpochMilli(entity.getUpdatedAt()));
    }

    @Transactional(readOnly = true)
    public CoursewareDetail getForManagement(long courseId, long coursewareId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        CoursewareEntity entity = requireEntity(courseId, coursewareId);
        return detail(entity, codec.fromJson(entity.getBody()));
    }

    @Transactional(readOnly = true)
    public CoursewarePlayView getPlayView(long courseId, long coursewareId) {
        courseAccess.requireLearningAccess(courseId, currentActor.require());
        CoursewareEntity entity = requireLearnable(courseId, coursewareId);
        Stage stripped = StageProjections.withoutQuizSecrets(codec.fromJson(entity.getBody()));
        return new CoursewarePlayView(entity.getId(), entity.getTitle(),
                codec.toTree(stripped), assetUrls(entity.getId(), stripped));
    }

    @Transactional
    public CoursewareDetail create(long courseId, String title) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        Stage stage = new Stage(title.trim(), "default", List.of());
        requireValidDocument(stage);
        CoursewareEntity entity = new CoursewareEntity(courseId, stage.title(), 0,
                codec.toJson(stage), LocalDateTime.now(ZoneOffset.UTC));
        coursewareMapper.insert(entity);
        return detail(entity, stage);
    }

    /** OSS 对象由 purger 按数据库行导出并同事务入队;行导不出的残余由孤儿清理器回收 */
    @Transactional
    public void delete(long courseId, long coursewareId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        // 锁课件行:学生的浏览 / 作答记录与生成流水线在同一事务内读它,删除后不再有子行落地
        requireForUpdate(courseId, coursewareId);
        purger.purgeCoursewares(List.of(coursewareId));
    }

    @Transactional
    public CoursewareSummary publish(long courseId, long coursewareId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        CoursewareEntity entity = requireForUpdate(courseId, coursewareId);
        if (entity.isPublished()) {
            throw new ConflictException("课件已发布");
        }
        if (entity.getSceneCount() == null || entity.getSceneCount() == 0) {
            throw new ConflictException("课件还没有任何页面,不能发布");
        }
        entity.publish(LocalDateTime.now(ZoneOffset.UTC));
        coursewareMapper.updateById(entity);
        return summary(entity);
    }

    @Transactional
    public CoursewareSummary unpublish(long courseId, long coursewareId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        CoursewareEntity entity = requireForUpdate(courseId, coursewareId);
        if (!entity.isPublished()) {
            throw new ConflictException("课件未发布");
        }
        entity.unpublish(LocalDateTime.now(ZoneOffset.UTC));
        coursewareMapper.updateById(entity);
        return summary(entity);
    }

    // ---- 内部操作(public 只为同模块其他服务可用;无权限检查:调用方已在入口完成授权,后台线程没有请求上下文) ----

    @Transactional(readOnly = true)
    public Stage getInternal(long courseId, long coursewareId) {
        return codec.fromJson(requireEntity(courseId, coursewareId).getBody());
    }

    @Transactional(readOnly = true)
    public Stage getPlayStageInternal(long courseId, long coursewareId) {
        return StageProjections.withoutQuizSecrets(getInternal(courseId, coursewareId));
    }

    @Transactional(readOnly = true)
    public VersionedStage getWithVersionInternal(long courseId, long coursewareId) {
        CoursewareEntity entity = requireEntity(courseId, coursewareId);
        return new VersionedStage(codec.fromJson(entity.getBody()),
                entity.getVersion() == null ? 0 : entity.getVersion());
    }

    /**
     * 写入(唯一路径):调用方已持有行锁(requireForUpdate),整份替换 body,version +1;
     * 不再被引用的音频与图片与写入同事务入队删除。
     * 引用的对象键必须归属本课件(courseware/{id}/ 前缀)——这是同 bucket 下跨课程读 / 删的唯一闸门。
     */
    @Transactional
    public VersionedStage saveLocked(CoursewareEntity locked, Stage stage) {
        requireValidDocument(stage);
        requireOwnedAssets(locked.getId(), stage);
        Stage previous = codec.fromJson(locked.getBody());
        Set<String> released = new HashSet<>(referencedKeys(previous));
        released.removeAll(referencedKeys(stage));
        Set<String> removedScenes = new HashSet<>();
        previous.scenes().forEach(scene -> removedScenes.add(scene.id()));
        stage.scenes().forEach(scene -> removedScenes.remove(scene.id()));
        locked.replaceContent(stage.title(), stage.scenes().size(), codec.toJson(stage),
                LocalDateTime.now(ZoneOffset.UTC));
        int rows = coursewareMapper.updateById(locked);
        if (rows != 1) {
            throw new ConflictException("课件状态已变化,写入未生效");
        }
        if (!released.isEmpty()) {
            assetStorage.enqueueDelete(released);
        }
        return new VersionedStage(stage, locked.getVersion());
    }

    /** 写操作入口:课件行锁,串行化同一课件的全部写入(生成流水线与手工编辑) */
    public CoursewareEntity requireForUpdate(long courseId, long coursewareId) {
        CoursewareEntity entity = coursewareMapper.selectForUpdate(coursewareId);
        if (entity == null || entity.getCourseId() != courseId) {
            throw new NotFoundException("课件不存在");
        }
        return entity;
    }

    /** 学生面的课件前置:存在于本课程且已发布(未发布与不存在同一文案,不泄露存在性) */
    public CoursewareEntity requireLearnable(long courseId, long coursewareId) {
        CoursewareEntity entity = requireEntity(courseId, coursewareId);
        if (!entity.isPublished()) {
            throw new NotFoundException("课件不存在");
        }
        return entity;
    }

    public CoursewareEntity requireEntity(long courseId, long coursewareId) {
        CoursewareEntity entity = coursewareMapper.selectOne(
                new LambdaQueryWrapper<CoursewareEntity>()
                        .eq(CoursewareEntity::getId, coursewareId)
                        .eq(CoursewareEntity::getCourseId, courseId));
        if (entity == null) {
            throw new NotFoundException("课件不存在");
        }
        return entity;
    }

    public CoursewareDetail detail(CoursewareEntity entity, Stage stage) {
        return new CoursewareDetail(entity.getId(), entity.getTitle(), entity.isPublished(),
                entity.getVersion() == null ? 0 : entity.getVersion(),
                codec.toTree(stage), assetUrls(entity.getId(), stage),
                toEpochMilli(entity.getCreatedAt()), toEpochMilli(entity.getUpdatedAt()));
    }

    /**
     * 文档里引用的对象键(讲稿音频、图片)→ 短期预签名 GET URL;文档本身不含地址。
     * 只签本课件前缀下的键——写入闸门之外的第二道防线,历史数据里的越界键在这里静默失效。
     */
    Map<String, String> assetUrls(long coursewareId, Stage stage) {
        Map<String, String> urls = new LinkedHashMap<>();
        for (String key : referencedKeys(stage)) {
            if (CoursewareAssetStorage.isOwnedKey(coursewareId, key)) {
                urls.put(key, assetStorage.presignGet(key).toString());
            }
        }
        return urls;
    }

    private static void requireOwnedAssets(long coursewareId, Stage stage) {
        for (String key : referencedKeys(stage)) {
            if (!CoursewareAssetStorage.isOwnedKey(coursewareId, key)) {
                throw new BadRequestException("对象键 \"" + key + "\" 不属于本课件,不能引用");
            }
        }
    }

    static Set<String> referencedKeys(Stage stage) {
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (Stage.Scene scene : stage.scenes()) {
            for (Stage.SpeechSegment segment : scene.speech()) {
                if (segment.audioPath() != null && !segment.audioPath().isBlank()) {
                    keys.add(segment.audioPath());
                }
            }
            collectImageKeys(scene.blocks(), keys);
            if (scene.video() != null && scene.video().src() != null && !scene.video().src().isBlank()) {
                keys.add(scene.video().src());
            }
        }
        return keys;
    }

    static void collectImageKeys(List<Block> blocks, Set<String> out) {
        for (Block block : blocks) {
            if (block instanceof Block.Image image) {
                out.add(image.src());
            } else if (block instanceof Block.Columns columns) {
                columns.children().forEach(column -> collectImageKeys(column, out));
            }
        }
    }

    /**
     * 文档结构校验:标题 / 页数组齐全、页 id 唯一;零页是合法草稿(发布时另查)。
     * 页内语义(块规则、讲稿规则)不在这里查——那是每条写入路径对**它触及的页**的责任
     * (StageCommands 校验触及页、putSceneInternal 校验整页);在这里查全部页会让一页的历史遗留问题挡住别的页的修改。
     */
    private void requireValidDocument(Stage stage) {
        if (stage.title() == null || stage.title().isBlank()) {
            throw new BadRequestException("课件缺少 title");
        }
        if (stage.scenes() == null) {
            throw new BadRequestException("课件缺少 scenes");
        }
        Set<String> ids = new HashSet<>();
        for (Stage.Scene scene : stage.scenes()) {
            if (scene.id() == null || scene.id().isBlank()) {
                throw new BadRequestException("页面缺少 id");
            }
            if (!ids.add(scene.id())) {
                throw new BadRequestException("页面 id 重复:" + scene.id());
            }
        }
    }

    static long toEpochMilli(LocalDateTime time) {
        return time.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}
