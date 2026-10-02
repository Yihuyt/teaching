package cn.utcy.teaching.identity.infrastructure;

import cn.utcy.teaching.shared.actor.ActorPrincipal;
import cn.utcy.teaching.identity.domain.CredentialState;
import cn.utcy.teaching.identity.domain.UserAccount;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;

@Component
class AccountSessionValidationFilter extends OncePerRequestFilter {

    private final UserAccountMapper accountMapper;
    private final ObjectMapper objectMapper;

    AccountSessionValidationFilter(
            UserAccountMapper accountMapper,
            ObjectMapper objectMapper
    ) {
        this.accountMapper = accountMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof ActorPrincipal principal)) {
            filterChain.doFilter(request, response);
            return;
        }

        UserAccount account = accountMapper.selectById(principal.userId());
        if (!matchesCurrentIdentity(principal, account)) {
            SecurityContextHolder.clearContext();
            HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
            SecurityProblemWriter.write(
                    response,
                    objectMapper,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    "登录状态已失效",
                    "账户状态、角色或凭据已变更，请重新登录");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean matchesCurrentIdentity(ActorPrincipal principal, UserAccount account) {
        return account != null
                && principal.enabled()
                && account.isEnabled()
                && account.getCredentialState() == CredentialState.ACTIVE
                && Objects.equals(principal.username(), account.getUsername())
                && Objects.equals(principal.password(), account.getPasswordHash())
                && principal.role() == account.getRole()
                && principal.mustResetPassword() == account.isMustResetPassword();
    }
}
