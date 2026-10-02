package cn.utcy.teaching.notification.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.actor.OwnershipPolicy;
import cn.utcy.teaching.shared.web.PageResponse;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.notification.domain.Announcement;
import cn.utcy.teaching.notification.infrastructure.AnnouncementMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AnnouncementApplicationService {

    private final AnnouncementMapper announcements;
    private final CourseAccess courseAccess;
    private final CurrentActor currentActor;
    private final OwnershipPolicy ownership;

    public AnnouncementApplicationService(
            AnnouncementMapper announcements,
            CourseAccess courseAccess,
            CurrentActor currentActor,
            OwnershipPolicy ownership
    ) {
        this.announcements = announcements;
        this.courseAccess = courseAccess;
        this.currentActor = currentActor;
        this.ownership = ownership;
    }

    @Transactional(readOnly = true)
    /** 公告一经创建即可见;课程公告:负责人直接可读,其他人须为课程成员且课程已发布 */
    public PageResponse<AnnouncementView> list(Long courseId, int page, int size) {
        Actor actor = currentActor.require();
        if (courseId != null && !courseAccess.canManage(courseId, actor)) {
            courseAccess.requireLearningAccess(courseId, actor);
        }
        Page<Announcement> result = announcements.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<Announcement>()
                        .eq(courseId != null, Announcement::getCourseId, courseId)
                        .isNull(courseId == null, Announcement::getCourseId)
                        .orderByDesc(Announcement::getCreatedAt)
                        .orderByDesc(Announcement::getId));
        return PageResponse.of(result.getRecords().stream().map(this::view).toList(),
                result.getTotal(), page, size);
    }

    @Transactional
    public AnnouncementView create(Long courseId, String title, String contentMarkdown) {
        Actor actor = currentActor.require();
        requireManage(courseId, actor);
        Announcement announcement = Announcement.create(
                courseId, actor.userId(), title.trim(), contentMarkdown);
        requireMutation(announcements.insert(announcement), "公告创建未生效");
        return view(announcement);
    }

    @Transactional
    public AnnouncementView update(long announcementId, String title, String contentMarkdown) {
        Announcement announcement = requireForUpdate(announcementId);
        requireManage(announcement.getCourseId(), currentActor.require());
        announcement.update(title.trim(), contentMarkdown);
        requireMutation(
                announcements.updateById(announcement),
                "公告状态已变化，更新未生效");
        return view(announcement);
    }

    @Transactional
    public void delete(long announcementId) {
        Announcement announcement = requireForUpdate(announcementId);
        requireManage(announcement.getCourseId(), currentActor.require());
        requireMutation(
                announcements.deleteById(announcementId),
                "公告状态已变化，删除未生效");
    }

    private void requireManage(Long courseId, Actor actor) {
        if (courseId == null) {
            ownership.requirePlatformAdministrator(actor);
        } else {
            courseAccess.requireManagementAccess(courseId, actor);
        }
    }

    private Announcement require(long id) {
        Announcement announcement = announcements.selectById(id);
        if (announcement == null) {
            throw new NotFoundException("公告不存在");
        }
        return announcement;
    }

    private Announcement requireForUpdate(long id) {
        Announcement announcement = announcements.selectForUpdate(id);
        if (announcement == null) {
            throw new NotFoundException("公告不存在");
        }
        return announcement;
    }

    private void requireMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new ConflictException(message);
        }
    }

    private AnnouncementView view(Announcement announcement) {
        return new AnnouncementView(
                announcement.getId(),
                announcement.getCourseId(),
                announcement.getOwnerId(),
                announcement.getTitle(),
                announcement.getContentMarkdown(),
                announcement.getCreatedAt(),
                announcement.getUpdatedAt());
    }

    public record AnnouncementView(
            long id,
            @Schema(nullable = true) Long courseId,
            long ownerId,
            String title,
            String contentMarkdown,
            /** 创建即发布,createdAt 就是发布时间 */
            Instant createdAt,
            Instant updatedAt
    ) {
    }
}
