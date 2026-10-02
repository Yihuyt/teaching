package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseDeletionGuard;
import cn.utcy.teaching.shared.actor.OwnershipPolicy;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.course.domain.Course;
import cn.utcy.teaching.course.domain.CourseOutlineItem;
import cn.utcy.teaching.course.infrastructure.CourseMapper;
import cn.utcy.teaching.course.infrastructure.CourseMemberMapper;
import cn.utcy.teaching.course.infrastructure.CourseOutlineItemMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseDeletionOrderTest {

    private static final long COURSE_ID = 17L;
    private static final Actor OWNER = new Actor(7L, "teacher", SystemRole.TEACHER);

    @BeforeAll
    static void initializeMybatisMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CourseOutlineItem.class);
    }

    @Test
    void guardsRunBeforeOutlineItemsAndCourseRowAreDeleted() {
        CourseMapper courses = mock(CourseMapper.class);
        CourseOutlineItemMapper outlineItems = mock(CourseOutlineItemMapper.class);
        CourseDeletionGuard first = mock(CourseDeletionGuard.class);
        CourseDeletionGuard second = mock(CourseDeletionGuard.class);
        when(courses.selectForUpdate(COURSE_ID)).thenReturn(course());
        when(courses.deleteById(COURSE_ID)).thenReturn(1);
        CourseApplicationService service = service(courses, outlineItems, List.of(first, second));

        service.delete(COURSE_ID);

        InOrder order = inOrder(first, second, outlineItems, courses);
        order.verify(first).beforeCourseDeleted(COURSE_ID);
        order.verify(second).beforeCourseDeleted(COURSE_ID);
        order.verify(outlineItems).delete(any(Wrapper.class));
        order.verify(courses).deleteById(COURSE_ID);
    }

    @Test
    void rejectingGuardStopsDeletionBeforeAnyRowIsRemoved() {
        CourseMapper courses = mock(CourseMapper.class);
        CourseOutlineItemMapper outlineItems = mock(CourseOutlineItemMapper.class);
        CourseDeletionGuard first = mock(CourseDeletionGuard.class);
        CourseDeletionGuard second = mock(CourseDeletionGuard.class);
        when(courses.selectForUpdate(COURSE_ID)).thenReturn(course());
        doThrow(new ConflictException("守卫拒绝")).when(first).beforeCourseDeleted(COURSE_ID);
        CourseApplicationService service = service(courses, outlineItems, List.of(first, second));

        assertThatThrownBy(() -> service.delete(COURSE_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessage("守卫拒绝");

        verify(second, never()).beforeCourseDeleted(COURSE_ID);
        verify(outlineItems, never()).delete(any(Wrapper.class));
        verify(courses, never()).deleteById(COURSE_ID);
    }

    private CourseApplicationService service(
            CourseMapper courses,
            CourseOutlineItemMapper outlineItems,
            List<CourseDeletionGuard> guards
    ) {
        return new CourseApplicationService(
                courses,
                outlineItems,
                mock(cn.utcy.teaching.course.infrastructure.CourseUnitMapper.class),
                mock(CourseMemberMapper.class),
                () -> OWNER,
                new OwnershipPolicy(),
                mock(CourseJoinCodeGenerator.class),
                guards);
    }

    private Course course() {
        Instant now = Instant.parse("2026-07-29T00:00:00Z");
        return new Course(
                COURSE_ID,
                OWNER.userId(),
                "A1B2C3D4E5",
                "软件工程",
                "",
                true,
                now,
                now);
    }
}
