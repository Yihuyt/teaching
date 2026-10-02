package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.actor.OwnershipPolicy;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.domain.Course;
import cn.utcy.teaching.course.domain.CourseMember;
import cn.utcy.teaching.course.infrastructure.CourseMapper;
import cn.utcy.teaching.course.infrastructure.CourseMemberMapper;
import cn.utcy.teaching.course.infrastructure.CourseOutlineItemMapper;
import cn.utcy.teaching.course.infrastructure.CourseUnitMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CoursePublicationTest {

    private static final long COURSE_ID = 17L;
    private static final String JOIN_CODE = "A1B2C3D4E5";
    private static final Actor OWNER = new Actor(7L, "teacher", SystemRole.TEACHER);
    private static final Actor STUDENT = new Actor(29L, "student", SystemRole.STUDENT);

    @BeforeAll
    static void initializeTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, ""),
                Course.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(configuration, ""),
                CourseMember.class);
    }

    @Test
    void creatingACourseDoesNotEnrolTheCreator() {
        Fixture fixture = fixture(false);
        when(fixture.currentActor.require()).thenReturn(OWNER);
        when(fixture.courses.insert(any(Course.class))).thenAnswer(call -> {
            Course created = call.getArgument(0);
            ReflectionTestUtils.setField(created, "id", COURSE_ID);
            return 1;
        });

        fixture.service.create("软件工程", "");

        verify(fixture.members, never()).insert(any(CourseMember.class));
    }

    @Test
    void publishingMakesTheCourseJoinable() {
        Fixture fixture = fixture(false);
        when(fixture.currentActor.require()).thenReturn(OWNER, STUDENT);
        when(fixture.courses.updateById(fixture.course)).thenReturn(1);
        when(fixture.members.exists(any())).thenReturn(false);
        when(fixture.members.insert(any(CourseMember.class))).thenReturn(1);

        CourseApplicationService.CourseView published = fixture.service.publish(COURSE_ID);
        CourseApplicationService.CourseView joined = fixture.service.join(JOIN_CODE);

        assertThat(published.published()).isTrue();
        assertThat(fixture.course.isPublished()).isTrue();
        assertThat(joined.id()).isEqualTo(COURSE_ID);
        verify(fixture.courses).updateById(fixture.course);
        verify(fixture.members).insert(any(CourseMember.class));
    }

    @Test
    void unpublishingBlocksJoiningAgain() {
        Fixture fixture = fixture(true);
        when(fixture.currentActor.require()).thenReturn(OWNER, STUDENT);
        when(fixture.courses.updateById(fixture.course)).thenReturn(1);

        CourseApplicationService.CourseView unpublished = fixture.service.unpublish(COURSE_ID);

        assertThat(unpublished.published()).isFalse();
        assertThat(fixture.course.isPublished()).isFalse();
        assertThatThrownBy(() -> fixture.service.join(JOIN_CODE))
                .isInstanceOf(ConflictException.class)
                .hasMessage("课程尚未发布，暂时无法加入");
        verify(fixture.members, never()).insert(any(CourseMember.class));
    }

    @Test
    void publishingAnAlreadyPublishedCourseIsRejected() {
        Fixture fixture = fixture(true);
        when(fixture.currentActor.require()).thenReturn(OWNER);

        assertThatThrownBy(() -> fixture.service.publish(COURSE_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("课程已发布");

        verify(fixture.courses, never()).updateById(any(Course.class));
    }

    @Test
    void unpublishingAnUnpublishedCourseIsRejected() {
        Fixture fixture = fixture(false);
        when(fixture.currentActor.require()).thenReturn(OWNER);

        assertThatThrownBy(() -> fixture.service.unpublish(COURSE_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("课程未发布");

        verify(fixture.courses, never()).updateById(any(Course.class));
    }

    @Test
    void publishLocksCourseAndRejectsZeroAffectedRows() {
        Fixture fixture = fixture(false);
        when(fixture.currentActor.require()).thenReturn(OWNER);
        when(fixture.courses.updateById(fixture.course)).thenReturn(0);

        assertThatThrownBy(() -> fixture.service.publish(COURSE_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("课程状态已变化，发布未生效");

        verify(fixture.courses).selectForUpdate(COURSE_ID);
        verify(fixture.courses, never()).selectById(COURSE_ID);
    }

    private Fixture fixture(boolean published) {
        CourseMapper courses = mock(CourseMapper.class);
        CourseMemberMapper members = mock(CourseMemberMapper.class);
        CurrentActor currentActor = mock(CurrentActor.class);
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        Course course = new Course(
                COURSE_ID,
                OWNER.userId(),
                JOIN_CODE,
                "软件工程",
                "",
                published,
                now,
                now);
        when(courses.selectForUpdate(COURSE_ID)).thenReturn(course);
        when(courses.selectByJoinCodeForUpdate(JOIN_CODE)).thenReturn(course);
        CourseApplicationService service = new CourseApplicationService(
                courses,
                mock(CourseOutlineItemMapper.class),
                mock(CourseUnitMapper.class),
                members,
                currentActor,
                new OwnershipPolicy(),
                mock(CourseJoinCodeGenerator.class),
                List.of());
        return new Fixture(courses, members, currentActor, course, service);
    }

    private record Fixture(
            CourseMapper courses,
            CourseMemberMapper members,
            CurrentActor currentActor,
            Course course,
            CourseApplicationService service
    ) {
    }
}
