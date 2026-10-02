package cn.utcy.teaching.programming.api;

import cn.utcy.teaching.shared.error.ApiExceptionHandler;
import cn.utcy.teaching.programming.application.ProblemPackageImportApplicationService;
import cn.utcy.teaching.programming.application.ProgrammingProblemApplicationService;
import cn.utcy.teaching.programming.application.RejudgeApplicationService;
import cn.utcy.teaching.programming.application.SubmissionApplicationService;
import cn.utcy.teaching.programming.application.TestcaseApplicationService;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import cn.utcy.teaching.programming.domain.SubmissionStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourseProgrammingProblemControllerContractTest {
    private MockMvc mvc;
    private AnnotationConfigWebApplicationContext context;
    private ProgrammingProblemApplicationService problems;
    private ProblemPackageImportApplicationService packageImports;
    private TestcaseApplicationService testcases;
    private SubmissionApplicationService submissions;
    private RejudgeApplicationService rejudge;

    @BeforeEach
    void setUp() {
        problems = mock(ProgrammingProblemApplicationService.class);
        packageImports = mock(ProblemPackageImportApplicationService.class);
        testcases = mock(TestcaseApplicationService.class);
        submissions = mock(SubmissionApplicationService.class);
        rejudge = mock(RejudgeApplicationService.class);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.addBeanFactoryPostProcessor(beanFactory -> {
            beanFactory.registerSingleton("programmingProblemApplicationService", problems);
            beanFactory.registerSingleton("problemPackageImportApplicationService", packageImports);
            beanFactory.registerSingleton("testcaseApplicationService", testcases);
            beanFactory.registerSingleton("submissionApplicationService", submissions);
            beanFactory.registerSingleton("rejudgeApplicationService", rejudge);
        });
        context.register(
                TestWebConfiguration.class,
                CourseProgrammingProblemController.class,
                ProgrammingEnumConversionConfiguration.class,
                ApiExceptionHandler.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void packageImportRoutesToCourseImport() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "problem.zip", "application/zip", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/v1/courses/6/programming-problems/package-imports")
                        .file(file)
                        .param("difficulty", "easy"))
                .andExpect(status().isCreated());

        var upload = forClass(MultipartFile.class);
        verify(packageImports).importPackage(eq(6L), upload.capture(), eq(ProblemDifficulty.EASY));
        assertThat(upload.getValue().getOriginalFilename()).isEqualTo("problem.zip");
    }

    @Test
    void submissionIsScopedToCourse() throws Exception {
        mvc.perform(post("/api/v1/courses/6/programming-problems/9/submissions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"language": "CPP20", "sourceCode": "int main() { return 0; }"}
                                """))
                .andExpect(status().isCreated());

        verify(submissions).submit(6L, 9L, ProgrammingLanguage.CPP20, "int main() { return 0; }");
    }

    @Test
    void mySubmissionsAndDetailAreScopedToTheProblem() throws Exception {
        when(submissions.listMine(6L, 9L)).thenReturn(List.of());

        mvc.perform(get("/api/v1/courses/6/programming-problems/9/submissions"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/courses/6/programming-problems/9/submissions/31"))
                .andExpect(status().isOk());

        verify(submissions).listMine(6L, 9L);
        verify(submissions).get(6L, 9L, 31L);
    }

    @Test
    void rejudgeCommandsAreScopedToTheProblem() throws Exception {
        mvc.perform(post("/api/v1/courses/6/programming-problems/9/submissions/31/rejudge"))
                .andExpect(status().isNoContent());
        verify(rejudge).rejudgeSubmission(6L, 9L, 31L);

        mvc.perform(post("/api/v1/courses/6/programming-problems/9/rejudges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
        verify(rejudge).rejudgeProblem(6L, 9L, Set.of());

        mvc.perform(post("/api/v1/courses/6/programming-problems/9/rejudges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"statuses\": [\"WRONG_ANSWER\"]}"))
                .andExpect(status().isOk());
        verify(rejudge).rejudgeProblem(6L, 9L, Set.of(SubmissionStatus.WRONG_ANSWER));
    }

    @Test
    void deletionIsScopedToCourseAndBatchRequiresIds() throws Exception {
        mvc.perform(delete("/api/v1/courses/6/programming-problems/9"))
                .andExpect(status().isNoContent());

        verify(problems).delete(6L, List.of(9L));

        mvc.perform(post("/api/v1/courses/6/programming-problems/deletions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\": [9, 10]}"))
                .andExpect(status().isNoContent());

        verify(problems).delete(6L, List.of(9L, 10L));

        mvc.perform(post("/api/v1/courses/6/programming-problems/deletions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\": []}"))
                .andExpect(status().isBadRequest());

        verify(problems, never()).delete(6L, List.of());
    }

    @Test
    void testcaseListingIsScopedToCourse() throws Exception {
        when(testcases.listCases(6L, 9L)).thenReturn(List.of());

        mvc.perform(get("/api/v1/courses/6/programming-problems/9/testcases"))
                .andExpect(status().isOk());

        verify(testcases).listCases(6L, 9L);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class TestWebConfiguration {
        @Bean
        static MethodValidationPostProcessor methodValidationPostProcessor() {
            return new MethodValidationPostProcessor();
        }
    }
}
