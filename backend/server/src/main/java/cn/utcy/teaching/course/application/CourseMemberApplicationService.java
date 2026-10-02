package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.domain.CourseMember;
import cn.utcy.teaching.course.infrastructure.CourseMemberMapper;
import cn.utcy.teaching.identity.application.AccountDirectory;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class CourseMemberApplicationService {

    private final CourseApplicationService courses;
    private final CourseMemberMapper members;
    private final AccountDirectory accounts;
    private final CurrentActor currentActor;

    public CourseMemberApplicationService(
            CourseApplicationService courses,
            CourseMemberMapper members,
            AccountDirectory accounts,
            CurrentActor currentActor
    ) {
        this.courses = courses;
        this.members = members;
        this.accounts = accounts;
        this.currentActor = currentActor;
    }

    @Transactional(readOnly = true)
    public List<MemberView> list(long courseId) {
        courses.requireManagementAccess(courseId, currentActor.require());
        return members.selectList(new LambdaQueryWrapper<CourseMember>()
                        .eq(CourseMember::getCourseId, courseId)
                        .orderByAsc(CourseMember::getJoinedAt)
                        .orderByAsc(CourseMember::getId))
                .stream()
                .map(this::view)
                .toList();
    }

    /** 课程全部成员的账号 id(无门禁;供学情等内部聚合使用,调用方自行鉴权) */
    @Transactional(readOnly = true)
    public List<Long> memberAccountIds(long courseId) {
        return members.selectList(new LambdaQueryWrapper<CourseMember>()
                        .select(CourseMember::getAccountId)
                        .eq(CourseMember::getCourseId, courseId)
                        .orderByAsc(CourseMember::getJoinedAt))
                .stream()
                .map(CourseMember::getAccountId)
                .toList();
    }

    @Transactional
    public void remove(long courseId, long accountId) {
        courses.requireManageableForUpdate(courseId, currentActor.require());
        int deleted = members.delete(new LambdaQueryWrapper<CourseMember>()
                .eq(CourseMember::getCourseId, courseId)
                .eq(CourseMember::getAccountId, accountId));
        if (deleted == 0) {
            throw new NotFoundException("课程成员不存在");
        }
        if (deleted != 1) {
            throw new ConflictException("课程成员数据不唯一，移除未生效");
        }
    }

    private MemberView view(CourseMember member) {
        AccountDirectory.AccountSummary account = accounts.require(member.getAccountId());
        return new MemberView(
                account.id(),
                account.username(),
                account.displayName(),
                account.role(),
                member.getJoinedAt());
    }

    public record MemberView(
            long accountId,
            String username,
            String displayName,
            SystemRole role,
            Instant joinedAt
    ) {
    }

}
