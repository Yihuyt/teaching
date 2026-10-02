package cn.utcy.teaching.blockcoding.application;

import cn.utcy.teaching.blockcoding.domain.BlockCodingProject;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingProjectMapper;
import cn.utcy.teaching.blockcoding.infrastructure.BlockCodingStorage;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.web.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class BlockCodingProjectService {
    /** sb3 上限：与前端提示一致；平台 multipart 上限(256MB)之下的业务约束 */
    static final int MAX_FILE_BYTES = 50 * 1024 * 1024;

    private final BlockCodingProjectMapper projects;
    private final BlockCodingStorage storage;
    private final CurrentActor currentActor;
    private final CourseAccess courseAccess;
    private final CourseBlockCodingConfigService courseConfig;
    private final BlockCodingProjectPurger purger;

    public BlockCodingProjectService(
            BlockCodingProjectMapper projects,
            BlockCodingStorage storage,
            CurrentActor currentActor,
            CourseAccess courseAccess,
            CourseBlockCodingConfigService courseConfig,
            BlockCodingProjectPurger purger
    ) {
        this.projects = projects;
        this.storage = storage;
        this.currentActor = currentActor;
        this.courseAccess = courseAccess;
        this.courseConfig = courseConfig;
        this.purger = purger;
    }

    public ProjectView create(long courseId, String name) {
        Actor actor = requireAccess(courseId);
        BlockCodingProject project = BlockCodingProject.create(courseId, actor.userId(), name);
        projects.insert(project);
        return ProjectView.of(project);
    }

    public PageResponse<ProjectView> list(long courseId, int page, int size) {
        Actor actor = requireAccess(courseId);
        long total = projects.countByCourseAndOwner(courseId, actor.userId());
        List<ProjectView> items = projects
                .selectPageByCourseAndOwner(courseId, actor.userId(), size, (page - 1) * size)
                .stream()
                .map(ProjectView::of)
                .toList();
        return PageResponse.of(items, total, page, size);
    }

    public ProjectView get(long courseId, long id) {
        return ProjectView.of(requireOwned(courseId, id));
    }

    public ProjectView rename(long courseId, long id, String name) {
        BlockCodingProject project = requireOwned(courseId, id);
        project.rename(name);
        projects.updateById(project);
        return ProjectView.of(project);
    }

    @Transactional
    public void delete(long courseId, long id) {
        Actor actor = requireAccess(courseId);
        // 锁作品行:进行中的对话在同一事务内读它,删除后不再有会话 / 消息落地
        BlockCodingProject project = projects.selectOwnedForUpdate(id, actor.userId());
        if (project == null || project.getCourseId() != courseId) {
            throw new NotFoundException("作品不存在");
        }
        purger.purge(project);
    }

    public ProjectView saveFile(long courseId, long id, byte[] content) {
        if (content == null || content.length == 0) {
            throw new BadRequestException("工程文件内容为空");
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new BadRequestException("工程文件超过 50MB 上限");
        }
        BlockCodingProject project = requireOwned(courseId, id);
        String objectKey = storage.objectKey(project.getOwnerAccountId(), project.getId());
        storage.put(objectKey, content);
        project.recordFile(objectKey, content.length);
        projects.updateById(project);
        return ProjectView.of(project);
    }

    public byte[] loadFile(long courseId, long id) {
        BlockCodingProject project = requireOwned(courseId, id);
        if (project.getOssObjectKey() == null) {
            throw new NotFoundException("该作品还没有保存过文件");
        }
        return storage.get(project.getOssObjectKey());
    }

    BlockCodingProject requireOwned(long courseId, long id) {
        Actor actor = requireAccess(courseId);
        BlockCodingProject project = projects.selectOwned(id, actor.userId());
        if (project == null || project.getCourseId() != courseId) {
            throw new NotFoundException("作品不存在");
        }
        return project;
    }

    long courseOf(long projectId) {
        BlockCodingProject project = projects.selectById(projectId);
        if (project == null) {
            throw new IllegalStateException("对话引用的作品不存在");
        }
        return project.getCourseId();
    }

    private Actor requireAccess(long courseId) {
        Actor actor = currentActor.require();
        if (!courseAccess.canManage(courseId, actor)) {
            courseConfig.requireEnabledForLearner(courseId);
        }
        return actor;
    }

    public record ProjectView(
            long id,
            long courseId,
            String name,
            boolean hasFile,
            @io.swagger.v3.oas.annotations.media.Schema(nullable = true) Instant fileUpdatedAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        static ProjectView of(BlockCodingProject project) {
            return new ProjectView(
                    project.getId(),
                    project.getCourseId(),
                    project.getName(),
                    project.getOssObjectKey() != null,
                    project.getFileUpdatedAt(),
                    project.getCreatedAt(),
                    project.getUpdatedAt());
        }
    }
}
