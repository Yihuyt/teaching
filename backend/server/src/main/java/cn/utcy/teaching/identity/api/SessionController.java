package cn.utcy.teaching.identity.api;

import cn.utcy.teaching.shared.actor.ActorPrincipal;
import cn.utcy.teaching.identity.application.AccountApplicationService;
import cn.utcy.teaching.identity.application.AccountView;
import cn.utcy.teaching.identity.infrastructure.LoginThrottle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/session")
public class SessionController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final AccountApplicationService accounts;
    private final LoginThrottle throttle;

    public SessionController(
            AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            AccountApplicationService accounts,
            LoginThrottle throttle
    ) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.accounts = accounts;
        this.throttle = throttle;
    }

    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken token) {
        return new CsrfView(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    public AccountView login(
            @Valid @RequestBody LoginRequest requestBody,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        // 来源地址已由 ForwardedHeaderFilter 按 X-Forwarded-For 还原(server.forward-headers-strategy=framework)
        String remoteAddress = request.getRemoteAddr();
        throttle.check(requestBody.username(), remoteAddress);
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            requestBody.username(), requestBody.password()));
        } catch (AuthenticationException exception) {
            throttle.recordFailure(requestBody.username(), remoteAddress);
            throw exception;
        }
        throttle.recordSuccess(requestBody.username());
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        return accounts.currentAccount();
    }

    @GetMapping
    public AccountView current() {
        return accounts.currentAccount();
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password
    ) {
    }

    public record CsrfView(String headerName, String token) {
    }
}
