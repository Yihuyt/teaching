package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseDeletionGuard;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.actor.OwnershipPolicy;
import cn.utcy.teaching.shared.web.PageResponse;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.domain.Course;
import cn.utcy.teaching.course.domain.CourseUnit;
import cn.utcy.teaching.course.domain.CourseMember;
import cn.utcy.teaching.course.domain.CourseOutlineItem;
import cn.utcy.teaching.course.infrastructure.CourseMapper;
import cn.utcy.teaching.course.infrastructure.CourseUnitMapper;
import cn.utcy.teaching.course.infrastructure.CourseMemberMapper;
import cn.utcy.teaching.course.infrastructure.CourseOutlineItemMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class CourseApplicationService implements CourseAccess {

    private final CourseMapper courses;
    private final CourseOutlineItemMapper outlineItems;
    private final CourseUnitMapper units;
    private final CourseMemberMapper members;
    private final CurrentActor currentActor;
    private final OwnershipPolicy ownership;
    private final CourseJoinCodeGenerator joinCodes;
    private final List<CourseDeletionGuard> deletionGuards;

    private static final Pattern JOIN_CODE_PATTERN = Pattern.compile("[0-9A-F]{10}");

    public CourseApplicationService(
            CourseMapper courses,
            CourseOutlineItemMapper outlineItems,
            CourseUnitMapper units,
            CourseMemberMapper members,
            CurrentActor currentActor,
            OwnershipPolicy ownership,
            CourseJoinCodeGenerator joinCodes,
            List<CourseDeletionGuard> deletionGuards
    ) {
        this.courses = courses;
        this.outlineItems = outlineItems;
        this.units = units;
        this.members = members;
        this.currentActor = currentActor;
        this.ownership = ownership;
        this.joinCodes = joinCodes;
        this.deletionGuards = List.copyOf(deletionGuards);
    }

    @Transactional(readOnly = true)
    public PageResponse<CourseView> list(
            int page,
            int size,
            String keyword,
            boolean management
    ) {
        Actor actor = currentActor.require();
        LambdaQueryWrapper<Course> query = new LambdaQueryWrapper<Course>()
                .like(keyword != null && !keyword.isBlank(), Course::getTitle, keyword)
                .orderByDesc(Course::getUpdatedAt);
        if (management) {
            if (!actor.role().canManageTeachingContent()) {
                throw new ForbiddenOperationException("当前角色不能管理课程");
            }
            query.eq(actor.role() == SystemRole.TEACHER, Course::getOwnerId, actor.userId());
        } else {
            List<Long> courseIds = memberCourseIds(actor.userId());
            if (courseIds.isEmpty()) {
                return PageResponse.of(List.of(), 0, page, size);
            }
            query.in(Course::getId, courseIds)
                    .eq(Course::isPublished, true);
        }
        Page<Course> result = courses.selectPage(Page.of(page, size), query);
        return PageResponse.of(
                result.getRecords().stream().map(this::view).toList(),
                result.getTotal(),
                page,
                size);
    }

    @Transactional(readOnly = true)
    public CourseView get(long courseId) {
        Course course = requireCourse(courseId);
        requireLearningAccess(courseId, currentActor.require());
        return view(course);
    }

    @Transactional(readOnly = true)
    public CourseManagementView getForManagement(long courseId) {
        Course course = requireCourse(courseId);
        requireManagementAccess(courseId, currentActor.require());
        return managementView(course);
    }

    @Transactional
    public CourseView create(String title, String descriptionMarkdown) {
        Actor actor = currentActor.require();
        if (!actor.role().canManageTeachingContent()) {
            throw new ForbiddenOperationException("当前角色不能创建课程");
        }
        Course course = Course.create(
                actor.userId(),
                joinCodes.next(),
                title.trim(),
                descriptionMarkdown);
        requireMutation(courses.insert(course), "课程创建未生效");
        return view(course);
    }

    @Transactional
    public CourseView join(String rawJoinCode) {
        Actor actor = currentActor.require();
        String joinCode = normalizeJoinCode(rawJoinCode);
        Course course = courses.selectByJoinCodeForUpdate(joinCode);
        if (course == null) {
            throw new NotFoundException("课程码无效");
        }
        if (!course.isPublished()) {
            throw new ConflictException("课程尚未发布，暂时无法加入");
        }
        if (members.exists(new LambdaQueryWrapper<CourseMember>()
                .eq(CourseMember::getCourseId, course.getId())
                .eq(CourseMember::getAccountId, actor.userId()))) {
            throw new ConflictException("你已经加入该课程");
        }
        requireMutation(
                members.insert(CourseMember.create(course.getId(), actor.userId())),
                "加入课程未生效");
        return view(course);
    }

    @Transactional
    public CourseView update(long courseId, String title, String descriptionMarkdown) {
        Course course = requireManageableForUpdate(courseId, currentActor.require());
        course.update(title.trim(), descriptionMarkdown);
        requireMutation(courses.updateById(course), "课程状态已变化，更新未生效");
        return view(course);
    }

    @Transactional
    public CourseView publish(long courseId) {
        Course course = requireManageableForUpdate(courseId, currentActor.require());
        if (course.isPublished()) {
            throw new ConflictException("课程已发布");
        }
        course.publish();
        requireMutation(courses.updateById(course), "课程状态已变化，发布未生效");
        return view(course);
    }

    @Transactional
    public CourseView unpublish(long courseId) {
        Course course = requireManageableForUpdate(courseId, currentActor.require());
        if (!course.isPublished()) {
            throw new ConflictException("课程未发布");
        }
        course.unpublish();
        requireMutation(courses.updateById(course), "课程状态已变化，取消发布未生效");
        return view(course);
    }

    @Transactional
    public CourseManagementView rotateJoinCode(long courseId) {
        Course course = requireManageableForUpdate(courseId, currentActor.require());
        course.rotateJoinCode(joinCodes.next());
        requireMutation(courses.updateById(course), "课程码状态已变化，更新未生效");
        return managementView(course);
    }

    /**
     * 删除课程:各模块先善后(可拒绝,并显式删掉各自随课程消失的行),再删课程模块自己的行
     * (课程内容、单元、成员),最后删课程行。数据库不设外键,没有任何一行靠级联消失。
     */
    @Transactional
    public void delete(long courseId) {
        requireManageableForUpdate(courseId, currentActor.require());
        deletionGuards.forEach(guard -> guard.beforeCourseDeleted(courseId));
        outlineItems.delete(new LambdaQueryWrapper<CourseOutlineItem>()
                .eq(CourseOutlineItem::getCourseId, courseId));
        units.delete(new LambdaQueryWrapper<CourseUnit>().eq(CourseUnit::getCourseId, courseId));
        members.delete(new LambdaQueryWrapper<CourseMember>().eq(CourseMember::getCourseId, courseId));
        requireMutation(courses.deleteById(courseId), "课程状态已变化，删除未生效");
    }

    @Override
    @Transactional(readOnly = true)
    public void requireLearningAccess(long courseId, Actor actor) {
        Course course = requireCourse(courseId);
        if (!course.isPublished()) {
            throw new ForbiddenOperationException("课程尚未发布");
        }
        if (!members.exists(new LambdaQueryWrapper<CourseMember>()
                .eq(CourseMember::getCourseId, courseId)
                .eq(CourseMember::getAccountId, actor.userId()))) {
            throw new ForbiddenOperationException("无权访问该课程");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void requireManagementAccess(long courseId, Actor actor) {
        Course course = requireCourse(courseId);
        ownership.requireContentManager(actor, course.getOwnerId());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean canManage(long courseId, Actor actor) {
        return ownership.isContentManager(actor, requireCourse(courseId).getOwnerId());
    }

    @Override
    @Transactional(readOnly = true)
    public long courseOwnerId(long courseId) {
        return requireCourse(courseId).getOwnerId();
    }

    Course requireCourse(long courseId) {
        Course course = courses.selectById(courseId);
        if (course == null) {
            throw new NotFoundException("课程不存在");
        }
        return course;
    }

    Course requireManageableForUpdate(long courseId, Actor actor) {
        Course course = courses.selectForUpdate(courseId);
        if (course == null) {
            throw new NotFoundException("课程不存在");
        }
        ownership.requireContentManager(actor, course.getOwnerId());
        return course;
    }

    void requireMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new ConflictException(message);
        }
    }

    private List<Long> memberCourseIds(long accountId) {
        return members.selectList(new LambdaQueryWrapper<CourseMember>()
                        .eq(CourseMember::getAccountId, accountId))
                .stream()
                .map(CourseMember::getCourseId)
                .toList();
    }

    private CourseView view(Course course) {
        return new CourseView(
                course.getId(),
                course.getOwnerId(),
                course.getTitle(),
                course.getDescriptionMarkdown(),
                course.isPublished(),
                course.getCreatedAt(),
                course.getUpdatedAt());
    }

    private CourseManagementView managementView(Course course) {
        return new CourseManagementView(
                course.getId(),
                course.getOwnerId(),
                course.getJoinCode(),
                course.getTitle(),
                course.getDescriptionMarkdown(),
                course.isPublished(),
                course.getCreatedAt(),
                course.getUpdatedAt());
    }

    private String normalizeJoinCode(String rawJoinCode) {
        String joinCode = rawJoinCode.trim().toUpperCase(Locale.ROOT);
        if (!JOIN_CODE_PATTERN.matcher(joinCode).matches()) {
            throw new BadRequestException("课程码格式不正确");
        }
        return joinCode;
    }

    public record CourseView(
            long id,
            long ownerId,
            String title,
            String descriptionMarkdown,
            boolean published,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record CourseManagementView(
            long id,
            long ownerId,
            String joinCode,
            String title,
            String descriptionMarkdown,
            boolean published,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

}
