package cn.utcy.teaching.course.api;

import cn.utcy.teaching.shared.error.ApiExceptionHandler;
import cn.utcy.teaching.course.application.CourseApplicationService;
import cn.utcy.teaching.course.application.CourseApplicationService.CourseView;
import cn.utcy.teaching.course.application.CourseLibraryDeletionImpactService;
import cn.utcy.teaching.course.application.CourseLibraryDeletionImpactService.DeletionImpactView;
import cn.utcy.teaching.course.application.CourseMemberApplicationService;
import cn.utcy.teaching.course.application.CourseOutlineApplicationService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourseControllerContractTest {

    private CourseApplicationService courses;
    private CourseOutlineApplicationService outline;
    private CourseLibraryDeletionImpactService deletionImpact;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        courses = mock(CourseApplicationService.class);
        outline = mock(CourseOutlineApplicationService.class);
        deletionImpact = mock(CourseLibraryDeletionImpactService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CourseController(
                        courses,
                        mock(CourseMemberApplicationService.class),
                        outline,
                        deletionImpact))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        JsonMapper.builder()
                                .addModule(new JavaTimeModule())
                                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                .build()))
                .build();
    }

    @Test
    void nullableParentMustBePresentButMayBeExplicitNull() throws Exception {
        String withoutParent = """
                {
                  "title": "第一章"
                }
                """;
        mvc.perform(post("/api/v1/courses/3/units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(withoutParent))
                .andExpect(status().isBadRequest());

        String nullParent = """
                {
                  "parentId": null,
                  "title": "第一章"
                }
                """;
        mvc.perform(post("/api/v1/courses/3/units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nullParent))
                .andExpect(status().isCreated());

        verify(outline).createUnit(3L, null, "第一章");
    }

    @Test
    void unitCreationRejectsRemovedPositionField() throws Exception {
        String oldRequest = """
                {
                  "parentId": null,
                  "title": "第一章",
                  "position": 1
                }
                """;

        mvc.perform(post("/api/v1/courses/3/units")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oldRequest))
                .andExpect(status().isBadRequest());
    }

    @Test
    void outlineItemMayTargetTopLevelOrAUnitAndHasNoCallerSuppliedPosition() throws Exception {
        mvc.perform(post("/api/v1/courses/3/outline-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "unitId": null,
                                  "itemType": "material",
                                  "contentId": 8
                                }
                                """))
                .andExpect(status().isCreated());

        verify(outline).addItem(3L, null, cn.utcy.teaching.shared.course.CourseOutlineItemType.MATERIAL, 8L);

        mvc.perform(post("/api/v1/courses/3/outline-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "unitId": 5,
                                  "itemType": "material",
                                  "contentId": 8
                                }
                                """))
                .andExpect(status().isCreated());

        verify(outline).addItem(3L, 5L, cn.utcy.teaching.shared.course.CourseOutlineItemType.MATERIAL, 8L);

        mvc.perform(post("/api/v1/courses/3/outline-items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "unitId": 5,
                                  "itemType": "material",
                                  "contentId": 8,
                                  "position": 1
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void joinCourseRequiresACodeAndPreservesTheEnteredValueForNormalization() throws Exception {
        mvc.perform(post("/api/v1/courses/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "joinCode": " a1b2c3d4e5 "
                                }
                                """))
                .andExpect(status().isCreated());

        verify(courses).join(" a1b2c3d4e5 ");

        mvc.perform(post("/api/v1/courses/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "joinCode": ""
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void publishUsesPostOnPublicationResource() throws Exception {
        when(courses.publish(1L)).thenReturn(courseView(true));

        mvc.perform(post("/api/v1/courses/1/publication"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true));

        verify(courses).publish(1L);
    }

    @Test
    void unpublishUsesDeleteOnPublicationResource() throws Exception {
        when(courses.unpublish(1L)).thenReturn(courseView(false));

        mvc.perform(delete("/api/v1/courses/1/publication"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(false));

        verify(courses).unpublish(1L);
    }

    @Test
    void updateRequestWithStatusIsRejected() throws Exception {
        mvc.perform(put("/api/v1/courses/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "软件工程",
                                  "descriptionMarkdown": "",
                                  "status": "published"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(courses, never()).update(anyLong(), any(), any());
    }

    @Test
    void libraryDeletionImpactTakesThreeIdListsAndAnswersAssociatedOnly() throws Exception {
        when(deletionImpact.impact(3L, List.of(8L), List.of(), List.of(4L)))
                .thenReturn(new DeletionImpactView(true));

        mvc.perform(post("/api/v1/courses/3/library/deletion-impact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialIds": [8],
                                  "questionIds": [],
                                  "problemIds": [4]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.associated").value(true))
                .andExpect(jsonPath("$.*", hasSize(1)));

        mvc.perform(post("/api/v1/courses/3/library/deletion-impact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialIds": [8],
                                  "questionIds": []
                                }
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/v1/courses/3/library/deletion-impact")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "materialIds": [8],
                                  "questionIds": [],
                                  "problemIds": null
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(deletionImpact, times(1)).impact(anyLong(), any(), any(), any());
    }

    private static CourseView courseView(boolean published) {
        return new CourseView(
                1L,
                7L,
                "软件工程",
                "",
                published,
                Instant.EPOCH,
                Instant.EPOCH);
    }
}
