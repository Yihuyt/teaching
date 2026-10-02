package cn.utcy.teaching.identity.infrastructure;

import cn.utcy.teaching.shared.actor.ActorPrincipal;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.identity.domain.CredentialState;
import cn.utcy.teaching.identity.domain.UserAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AccountSessionValidationFilterTest {

    private final UserAccountMapper accountMapper = mock(UserAccountMapper.class);
    private final AccountSessionValidationFilter filter =
            new AccountSessionValidationFilter(accountMapper, new ObjectMapper());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void actorPrincipalReportsDisabledAccountToSpringSecurity() {
        ActorPrincipal principal = principal(SystemRole.STUDENT, false, "encoded-password", false);

        assertThat(principal.isEnabled()).isFalse();
    }

    @Test
    void unchangedAccountContinuesWithExistingSession() throws Exception {
        ActorPrincipal principal = principal(SystemRole.STUDENT, true, "encoded-password", false);
        authenticate(principal);
        when(accountMapper.selectById(7L))
                .thenReturn(account(SystemRole.STUDENT, true, "encoded-password", false));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(session.isInvalid()).isFalse();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void disabledAccountImmediatelyInvalidatesStoredSession() throws Exception {
        ActorPrincipal principal = principal(SystemRole.STUDENT, true, "encoded-password", false);
        authenticate(principal);
        when(accountMapper.selectById(7L))
                .thenReturn(account(SystemRole.STUDENT, false, "encoded-password", false));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertRejected(response, session, chain);
    }

    @Test
    void changedRoleImmediatelyInvalidatesStoredSession() throws Exception {
        ActorPrincipal principal = principal(SystemRole.STUDENT, true, "encoded-password", false);
        authenticate(principal);
        when(accountMapper.selectById(7L))
                .thenReturn(account(SystemRole.TEACHER, true, "encoded-password", false));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertRejected(response, session, chain);
    }

    @Test
    void resetPasswordImmediatelyInvalidatesOlderSessionCredentials() throws Exception {
        ActorPrincipal principal = principal(SystemRole.STUDENT, true, "encoded-password", false);
        authenticate(principal);
        when(accountMapper.selectById(7L))
                .thenReturn(account(SystemRole.STUDENT, true, "new-password-hash", true));
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpSession session = new MockHttpSession();
        request.setSession(session);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertRejected(response, session, chain);
    }

    private void assertRejected(
            MockHttpServletResponse response,
            MockHttpSession session,
            MockFilterChain chain
    ) throws Exception {
        assertThat(chain.getRequest()).isNull();
        assertThat(session.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString())
                .contains("\"type\":\"urn:teaching:problem:security-error\"")
                .contains("\"title\":\"登录状态已失效\"")
                .contains("\"status\":401");
    }

    private void authenticate(ActorPrincipal principal) {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        principal.getAuthorities()));
    }

    private ActorPrincipal principal(
            SystemRole role,
            boolean enabled,
            String password,
            boolean mustResetPassword
    ) {
        return new ActorPrincipal(
                7L,
                "student",
                password,
                role,
                enabled,
                mustResetPassword);
    }

    private UserAccount account(
            SystemRole role,
            boolean enabled,
            String password,
            boolean mustResetPassword
    ) {
        return new UserAccount(
                7L,
                "student",
                password,
                CredentialState.ACTIVE,
                role,
                "学生",
                enabled,
                mustResetPassword,
                Instant.EPOCH,
                Instant.EPOCH);
    }
}
