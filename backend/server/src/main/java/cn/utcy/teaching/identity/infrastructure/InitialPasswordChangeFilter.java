package cn.utcy.teaching.identity.infrastructure;

import cn.utcy.teaching.shared.actor.ActorPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
class InitialPasswordChangeFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    InitialPasswordChangeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        Object principal = org.springframework.security.core.context.SecurityContextHolder
                .getContext()
                .getAuthentication() == null
                ? null
                : org.springframework.security.core.context.SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getPrincipal();
        if (principal instanceof ActorPrincipal actor
                && actor.mustResetPassword()
                && !isPasswordResetRequest(request)
                && !isSessionRequest(request)
                && !isPublicPlatformRequest(request)) {
            SecurityProblemWriter.write(
                    response,
                    objectMapper,
                    403,
                    "必须修改初始密码",
                    "当前账户必须先修改初始密码");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isPasswordResetRequest(HttpServletRequest request) {
        return HttpMethod.PUT.matches(request.getMethod())
                && request.getRequestURI().equals("/api/v1/accounts/me/password");
    }

    private boolean isSessionRequest(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/api/v1/session");
    }

    private boolean isPublicPlatformRequest(HttpServletRequest request) {
        return HttpMethod.GET.matches(request.getMethod())
                && request.getRequestURI().equals("/api/v1/platform/public");
    }
}
