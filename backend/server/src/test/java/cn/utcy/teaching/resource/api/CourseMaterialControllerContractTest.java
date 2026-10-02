package cn.utcy.teaching.resource.api;

import cn.utcy.teaching.shared.error.ApiExceptionHandler;
import cn.utcy.teaching.resource.application.CourseMaterialApplicationService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourseMaterialControllerContractTest {

    private CourseMaterialApplicationService materials;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        materials = mock(CourseMaterialApplicationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CourseMaterialController(materials))
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        JsonMapper.builder()
                                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                                .build()))
                .build();
    }

    @Test
    void materialManagementIsNestedUnderCourse() throws Exception {
        mvc.perform(get("/api/v1/courses/20/materials"))
                .andExpect(status().isOk());

        verify(materials).list(20L, null);
    }

    @Test
    void folderCreationUsesTheCourseMaterialContract() throws Exception {
        String valid = """
                {
                  "parentId": null,
                  "name": "课程资料"
                }
                """;
        mvc.perform(post("/api/v1/courses/20/materials/folders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(valid))
                .andExpect(status().isCreated());
        verify(materials).createFolder(20L, null, "课程资料");
    }

    @Test
    void deletionAlwaysGoesThroughTheListCommand() throws Exception {
        mvc.perform(delete("/api/v1/courses/20/materials/10"))
                .andExpect(status().isNoContent());
        verify(materials).delete(20L, List.of(10L));

        mvc.perform(post("/api/v1/courses/20/materials/deletions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\": [10, 11]}"))
                .andExpect(status().isNoContent());
        verify(materials).delete(20L, List.of(10L, 11L));

        mvc.perform(post("/api/v1/courses/20/materials/deletions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\": []}"))
                .andExpect(status().isBadRequest());
        verify(materials, never()).delete(20L, List.of());
    }
}
