package cn.utcy.teaching.identity.api;

import cn.utcy.teaching.identity.application.AccountApplicationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AccountControllerSecurityTest {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void changingOwnPasswordRevokesCurrentSession() {
        AccountApplicationService accounts = mock(AccountApplicationService.class);
        AccountController controller = new AccountController(accounts);
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("student", null));

        controller.changePassword(
                new AccountController.ChangePasswordRequest(
                        "current-password",
                        "new-password-123"),
                request);

        verify(accounts).changeOwnPassword("current-password", "new-password-123");
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
