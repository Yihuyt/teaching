package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.actor.OwnershipPolicy;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.domain.Course;
import cn.utcy.teaching.course.domain.CourseMember;
import cn.utcy.teaching.course.infrastructure.CourseMapper;
import cn.utcy.teaching.course.infrastructure.CourseMemberMapper;
import cn.utcy.teaching.course.infrastructure.CourseOutlineItemMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CourseLearningAccessTest {

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

    @ParameterizedTest
    @EnumSource(SystemRole.class)
    void everySystemRoleMayLearnFromPublishedCourseAfterJoining(SystemRole role) {
        Fixture fixture = fixture(role, true, true);

        assertThatCode(() -> fixture.service.requireLearningAccess(
                7L,
                fixture.actor))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @EnumSource(SystemRole.class)
    void noSystemRoleMayUseOrdinaryPortalWithoutCourseMembership(SystemRole role) {
        Fixture fixture = fixture(role, true, false);

        assertThatThrownBy(() -> fixture.service.requireLearningAccess(
                7L,
                fixture.actor))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("无权访问该课程");
    }

    @ParameterizedTest
    @EnumSource(SystemRole.class)
    void noSystemRoleMayReadUnpublishedCourseThroughOrdinaryPortal(SystemRole role) {
        Fixture fixture = fixture(role, false, true);

        assertThatThrownBy(() -> fixture.service.requireLearningAccess(
                7L,
                fixture.actor))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("课程尚未发布");
    }

    private Fixture fixture(
            SystemRole role,
            boolean published,
            boolean member
    ) {
        CourseMapper courses = mock(CourseMapper.class);
        CourseMemberMapper members = mock(CourseMemberMapper.class);
        Actor actor = new Actor(11L, role.value(), role);
        Instant now = Instant.parse("2026-07-24T00:00:00Z");
        when(courses.selectById(7L)).thenReturn(new Course(
                7L,
                11L,
                "A1B2C3D4E5",
                "课程",
                "",
                published,
                now,
                now));
        when(members.exists(any())).thenReturn(member);
        return new Fixture(
                actor,
                new CourseApplicationService(
                        courses,
                        mock(CourseOutlineItemMapper.class),
                mock(cn.utcy.teaching.course.infrastructure.CourseUnitMapper.class),
                        members,
                        () -> actor,
                        new OwnershipPolicy(),
                        mock(CourseJoinCodeGenerator.class),
                        List.of()));
    }

    private record Fixture(Actor actor, CourseApplicationService service) {
    }
}
