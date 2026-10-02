package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.OwnershipPolicy;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.domain.Course;
import cn.utcy.teaching.course.infrastructure.CourseMapper;
import cn.utcy.teaching.course.infrastructure.CourseMemberMapper;
import cn.utcy.teaching.course.infrastructure.CourseOutlineItemMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseJoinCodeRotationTest {

    @Test
    void courseOwnerCanReplaceTheJoinCode() {
        CourseMapper courses = mock(CourseMapper.class);
        CourseJoinCodeGenerator joinCodes = mock(CourseJoinCodeGenerator.class);
        Course stored = new Course(
                17L,
                7L,
                "A1B2C3D4E5",
                "软件工程",
                "",
                true,
                Instant.parse("2026-07-29T00:00:00Z"),
                Instant.parse("2026-07-29T00:00:00Z"));
        when(courses.selectForUpdate(17L)).thenReturn(stored);
        when(courses.updateById(stored)).thenReturn(1);
        when(joinCodes.next()).thenReturn("0011223344");
        CourseApplicationService service = new CourseApplicationService(
                courses,
                mock(CourseOutlineItemMapper.class),
                mock(cn.utcy.teaching.course.infrastructure.CourseUnitMapper.class),
                mock(CourseMemberMapper.class),
                () -> new Actor(7L, "teacher", SystemRole.TEACHER),
                new OwnershipPolicy(),
                joinCodes,
                List.of());

        CourseApplicationService.CourseManagementView result = service.rotateJoinCode(17L);

        assertThat(result.joinCode()).isEqualTo("0011223344");
        verify(courses).updateById(stored);
    }
}
