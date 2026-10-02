package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.actor.OwnershipPolicy;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.domain.Course;
import cn.utcy.teaching.course.domain.CourseMember;
import cn.utcy.teaching.course.infrastructure.CourseMapper;
import cn.utcy.teaching.course.infrastructure.CourseMemberMapper;
import cn.utcy.teaching.course.infrastructure.CourseOutlineItemMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseEnrollmentTest {

    private static final String JOIN_CODE = "A1B2C3D4E5";

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
    void everySystemRoleUsesTheSameCourseCodeEnrollmentFlow(SystemRole role) {
        Fixture fixture = fixture(role, true);
        when(fixture.members.exists(any())).thenReturn(false);
        when(fixture.members.insert(any(CourseMember.class))).thenReturn(1);

        CourseApplicationService.CourseView joined = fixture.service.join(" a1b2c3d4e5 ");

        assertThat(joined.id()).isEqualTo(17L);
        verify(fixture.courses).selectByJoinCodeForUpdate(JOIN_CODE);
        ArgumentCaptor<CourseMember> member = ArgumentCaptor.forClass(CourseMember.class);
        verify(fixture.members).insert(member.capture());
        assertThat(member.getValue().getCourseId()).isEqualTo(17L);
        assertThat(member.getValue().getAccountId()).isEqualTo(29L);
    }

    @Test
    void unpublishedCourseCannotBeJoined() {
        Fixture fixture = fixture(SystemRole.STUDENT, false);

        assertThatThrownBy(() -> fixture.service.join(JOIN_CODE))
                .isInstanceOf(ConflictException.class)
                .hasMessage("课程尚未发布，暂时无法加入");

        verify(fixture.members, never()).insert(any(CourseMember.class));
    }

    @Test
    void duplicateMembershipIsRejected() {
        Fixture fixture = fixture(SystemRole.STUDENT, true);
        when(fixture.members.exists(any())).thenReturn(true);

        assertThatThrownBy(() -> fixture.service.join(JOIN_CODE))
                .isInstanceOf(ConflictException.class)
                .hasMessage("你已经加入该课程");

        verify(fixture.members, never()).insert(any(CourseMember.class));
    }

    @Test
    void malformedAndUnknownCodesFailExplicitly() {
        Fixture fixture = fixture(SystemRole.STUDENT, true);

        assertThatThrownBy(() -> fixture.service.join("not-a-code"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("课程码格式不正确");

        when(fixture.courses.selectByJoinCodeForUpdate("ABCDEF1234")).thenReturn(null);
        assertThatThrownBy(() -> fixture.service.join("ABCDEF1234"))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("课程码无效");
    }

    private Fixture fixture(SystemRole role, boolean published) {
        CourseMapper courses = mock(CourseMapper.class);
        CourseMemberMapper members = mock(CourseMemberMapper.class);
        Actor actor = new Actor(29L, role.value(), role);
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        when(courses.selectByJoinCodeForUpdate(JOIN_CODE)).thenReturn(new Course(
                17L,
                5L,
                JOIN_CODE,
                "软件工程",
                "",
                published,
                now,
                now));
        CourseApplicationService service = new CourseApplicationService(
                courses,
                mock(CourseOutlineItemMapper.class),
                mock(cn.utcy.teaching.course.infrastructure.CourseUnitMapper.class),
                members,
                () -> actor,
                new OwnershipPolicy(),
                mock(CourseJoinCodeGenerator.class),
                List.of());
        return new Fixture(courses, members, service);
    }

    private record Fixture(
            CourseMapper courses,
            CourseMemberMapper members,
            CourseApplicationService service
    ) {
    }
}
