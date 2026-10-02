package cn.utcy.teaching.course.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.course.CourseContentReferenceSource;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.actor.SystemRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CourseLibraryDeletionImpactServiceTest {

    private static final long COURSE_ID = 7L;

    private final Actor actor = new Actor(11L, "teacher", SystemRole.TEACHER);
    private final CourseAccess courseAccess = mock(CourseAccess.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final CourseOutlineLinks outlineLinks = mock(CourseOutlineLinks.class);
    private final CourseContentReferenceSource graph = mock(CourseContentReferenceSource.class);
    private final CourseContentReferenceSource attempts = mock(CourseContentReferenceSource.class);
    private final CourseLibraryDeletionImpactService service = new CourseLibraryDeletionImpactService(
            courseAccess, currentActor, outlineLinks, List.of(graph, attempts));

    @Test
    void contentArrangedInCourseOutlineIsAssociated() {
        when(currentActor.require()).thenReturn(actor);
        when(outlineLinks.isLinked(COURSE_ID, CourseOutlineItemType.QUESTION, 5L)).thenReturn(true);

        var view = service.impact(COURSE_ID, List.of(), List.of(5L), List.of());

        assertThat(view.associated()).isTrue();
        verify(courseAccess).requireManagementAccess(COURSE_ID, actor);
        verify(graph, never()).references(anyLong(), any(), anyLong());
    }

    @Test
    void anyReferenceSourceSayingYesMakesContentAssociated() {
        when(currentActor.require()).thenReturn(actor);
        when(attempts.references(COURSE_ID, CourseOutlineItemType.PROGRAMMING_PROBLEM, 9L)).thenReturn(true);

        var view = service.impact(COURSE_ID, List.of(1L), List.of(), List.of(9L));

        assertThat(view.associated()).isTrue();
        verify(graph).references(COURSE_ID, CourseOutlineItemType.MATERIAL, 1L);
        verify(graph).references(COURSE_ID, CourseOutlineItemType.PROGRAMMING_PROBLEM, 9L);
    }

    @Test
    void contentNobodyReferencesIsNotAssociated() {
        when(currentActor.require()).thenReturn(actor);

        var view = service.impact(COURSE_ID, List.of(1L), List.of(2L), List.of(3L));

        assertThat(view.associated()).isFalse();
        verify(outlineLinks).isLinked(COURSE_ID, CourseOutlineItemType.MATERIAL, 1L);
        verify(outlineLinks).isLinked(COURSE_ID, CourseOutlineItemType.QUESTION, 2L);
        verify(outlineLinks).isLinked(COURSE_ID, CourseOutlineItemType.PROGRAMMING_PROBLEM, 3L);
        verify(attempts).references(COURSE_ID, CourseOutlineItemType.MATERIAL, 1L);
        verify(attempts).references(COURSE_ID, CourseOutlineItemType.QUESTION, 2L);
        verify(attempts).references(COURSE_ID, CourseOutlineItemType.PROGRAMMING_PROBLEM, 3L);
    }

    @Test
    void impactRequiresCourseManagementAccessBeforeAnyLookup() {
        when(currentActor.require()).thenReturn(actor);
        doThrow(new ForbiddenOperationException("仅课程负责人可操作"))
                .when(courseAccess).requireManagementAccess(COURSE_ID, actor);

        assertThatThrownBy(() -> service.impact(COURSE_ID, List.of(1L), List.of(), List.of()))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("仅课程负责人可操作");

        verify(outlineLinks, never()).isLinked(anyLong(), any(), anyLong());
        verify(graph, never()).references(anyLong(), any(), anyLong());
    }
}
