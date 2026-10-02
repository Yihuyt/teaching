package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
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
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseManagementQueryTest {

    @Test
    void teacherManagementQueryContainsOnlyOwnedCourses() {
        Fixture fixture = fixture(new Actor(7L, "teacher", SystemRole.TEACHER));

        fixture.service.list(1, 20, null, true);

        ArgumentCaptor<LambdaQueryWrapper<Course>> query = wrapperCaptor();
        verify(fixture.courses).selectPage(any(Page.class), query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("owner_id");
        assertThat(query.getValue().getParamNameValuePairs()).containsValue(7L);
    }

    @Test
    void rootManagementQueryMayListEveryCourse() {
        Fixture fixture = fixture(new Actor(1L, "root", SystemRole.ROOT));

        fixture.service.list(1, 20, null, true);

        ArgumentCaptor<LambdaQueryWrapper<Course>> query = wrapperCaptor();
        verify(fixture.courses).selectPage(any(Page.class), query.capture());
        assertThat(query.getValue().getSqlSegment()).doesNotContain("owner_id");
    }

    @Test
    void studentCannotUseManagementQuery() {
        Fixture fixture = fixture(new Actor(9L, "student", SystemRole.STUDENT));

        assertThatThrownBy(() -> fixture.service.list(1, 20, null, true))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("当前角色不能管理课程");
    }

    @ParameterizedTest
    @EnumSource(SystemRole.class)
    void ordinaryCourseQueryUsesTheSameMembershipAndPublishedScopeForEveryRole(
            SystemRole role
    ) {
        Fixture fixture = fixture(new Actor(9L, role.value(), role));
        when(fixture.members.selectList(any())).thenReturn(List.of(
                new CourseMember(
                        1L,
                        91L,
                        9L,
                        Instant.parse("2026-07-24T00:00:00Z"))));

        fixture.service.list(1, 20, null, false);

        ArgumentCaptor<LambdaQueryWrapper<Course>> query = wrapperCaptor();
        verify(fixture.courses).selectPage(any(Page.class), query.capture());
        assertThat(query.getValue().getSqlSegment())
                .contains("id", "published");
        assertThat(query.getValue().getParamNameValuePairs().values())
                .contains(91L, true)
                .doesNotContain(role);
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<LambdaQueryWrapper<Course>> wrapperCaptor() {
        return ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }

    private Fixture fixture(Actor actor) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                Course.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CourseMember.class);
        CourseMapper courses = mock(CourseMapper.class);
        CourseMemberMapper members = mock(CourseMemberMapper.class);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(actor);
        when(courses.selectPage(any(Page.class), any()))
                .thenReturn(Page.of(1, 20));
        CourseApplicationService service = new CourseApplicationService(
                courses,
                mock(CourseOutlineItemMapper.class),
                mock(cn.utcy.teaching.course.infrastructure.CourseUnitMapper.class),
                members,
                currentActor,
                mock(OwnershipPolicy.class),
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
