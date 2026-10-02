package cn.utcy.teaching.identity.api;

import cn.utcy.teaching.shared.error.ApiExceptionHandler;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.identity.application.AccountApplicationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockServletContext;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountControllerContractTest {

    private AccountApplicationService accounts;
    private MockMvc mvc;
    private AnnotationConfigWebApplicationContext context;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountApplicationService.class);
        SecurityContextRepository securityContexts = mock(SecurityContextRepository.class);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.addBeanFactoryPostProcessor(beanFactory -> {
            beanFactory.registerSingleton("accountApplicationService", accounts);
            beanFactory.registerSingleton(
                    "securityContextRepository",
                    securityContexts);
        });
        context.register(
                TestWebConfiguration.class,
                AccountController.class,
                IdentityEnumConversionConfiguration.class,
                ApiExceptionHandler.class);
        context.refresh();
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @Test
    void lowercaseRoleQueryUsesApiContractValue() throws Exception {
        mvc.perform(get("/api/v1/accounts").queryParam("role", "teacher"))
                .andExpect(status().isOk());

        verify(accounts).list(1, 20, SystemRole.TEACHER, null);
    }

    @Test
    void uppercaseRoleQueryIsRejected() throws Exception {
        mvc.perform(get("/api/v1/accounts").queryParam("role", "TEACHER"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数错误"));
    }

    @Test
    void pageMustBePositive() throws Exception {
        mvc.perform(get("/api/v1/accounts").queryParam("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数错误"));
    }

    @Test
    void accountStatusMustBePresentAndNonNull() throws Exception {
        mvc.perform(patch("/api/v1/accounts/9/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/api/v1/accounts/9/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":null}"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/api/v1/accounts/9/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk());

        verify(accounts).setEnabled(9L, false);
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
