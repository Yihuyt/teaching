package cn.utcy.teaching.notification.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.actor.OwnershipPolicy;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.notification.domain.Announcement;
import cn.utcy.teaching.notification.infrastructure.AnnouncementMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnnouncementApplicationServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void courseMemberListsAnnouncementsThroughLearningAccess() {
        Actor student = new Actor(9L, "student", SystemRole.STUDENT);
        Fixture fixture = fixture(student);
        when(fixture.courseAccess.canManage(7L, student)).thenReturn(false);
        when(fixture.announcements.selectPage(any(Page.class), any()))
                .thenReturn(Page.of(1, 20));

        fixture.service.list(7L, 1, 20);

        verify(fixture.courseAccess).requireLearningAccess(7L, student);
        ArgumentCaptor<LambdaQueryWrapper<Announcement>> query =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(fixture.announcements).selectPage(any(Page.class), query.capture());
        query.getValue().getSqlSegment();
        assertThat(query.getValue().getParamNameValuePairs().values()).contains(7L);
    }

    @Test
    void courseManagerListsAnnouncementsWithoutLearningAccessCheck() {
        Actor teacher = new Actor(7L, "teacher", SystemRole.TEACHER);
        Fixture fixture = fixture(teacher);
        when(fixture.courseAccess.canManage(8L, teacher)).thenReturn(true);
        when(fixture.announcements.selectPage(any(Page.class), any()))
                .thenReturn(Page.of(1, 20));

        fixture.service.list(8L, 1, 20);

        verify(fixture.courseAccess, never()).requireLearningAccess(any(Long.class), any(Actor.class));
        verify(fixture.announcements).selectPage(any(Page.class), any());
    }

    @Test
    void teacherCannotCreateGlobalAnnouncement() {
        Fixture fixture = fixture(new Actor(7L, "teacher", SystemRole.TEACHER));

        assertThatThrownBy(() ->
                fixture.service.create(null, "公告", "内容"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("此操作仅限平台管理员");
    }

    @Test
    void updateLocksAnnouncementAndRejectsZeroAffectedRows() {
        Fixture fixture = fixture(new Actor(1L, "root", SystemRole.ROOT));
        Announcement announcement = announcement();
        when(fixture.announcements.selectForUpdate(3L)).thenReturn(announcement);
        when(fixture.announcements.updateById(announcement)).thenReturn(0);

        assertThatThrownBy(() -> fixture.service.update(3L, "公告", "内容"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("公告状态已变化，更新未生效");

        verify(fixture.announcements).selectForUpdate(3L);
        verify(fixture.announcements, never()).selectById(3L);
    }

    @Test
    void deleteLocksAnnouncementAndRejectsZeroAffectedRows() {
        Fixture fixture = fixture(new Actor(1L, "root", SystemRole.ROOT));
        when(fixture.announcements.selectForUpdate(3L)).thenReturn(announcement());
        when(fixture.announcements.deleteById(3L)).thenReturn(0);

        assertThatThrownBy(() -> fixture.service.delete(3L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("公告状态已变化，删除未生效");

        verify(fixture.announcements).selectForUpdate(3L);
    }

    private Fixture fixture(Actor actor) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Announcement.class);
        AnnouncementMapper announcements = mock(AnnouncementMapper.class);
        CourseAccess courseAccess = mock(CourseAccess.class);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(actor);
        return new Fixture(
                announcements,
                courseAccess,
                new AnnouncementApplicationService(
                        announcements,
                        courseAccess,
                        currentActor,
                        new OwnershipPolicy()));
    }

    private Announcement announcement() {
        return new Announcement(
                3L,
                null,
                1L,
                "公告",
                "内容",
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private record Fixture(
            AnnouncementMapper announcements,
            CourseAccess courseAccess,
            AnnouncementApplicationService service
    ) {
    }
}
