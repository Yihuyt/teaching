package cn.utcy.teaching.identity.api;

import cn.utcy.teaching.shared.web.PageResponse;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.identity.application.AccountApplicationService;
import cn.utcy.teaching.identity.application.AccountView;
import cn.utcy.teaching.identity.domain.PasswordRules;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountApplicationService accounts;

    public AccountController(AccountApplicationService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public PageResponse<AccountView> list(
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) SystemRole role,
            @RequestParam(required = false) String keyword
    ) {
        return accounts.list(page, size, role, keyword);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountView create(@Valid @RequestBody CreateAccountRequest request) {
        return accounts.create(
                request.username(),
                request.initialPassword(),
                request.role(),
                request.displayName());
    }

    @PutMapping("/{accountId}/profile")
    public AccountView updateProfile(
            @PathVariable long accountId,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return accounts.updateProfile(accountId, request.displayName());
    }

    @PutMapping("/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            HttpServletRequest servletRequest
    ) {
        accounts.changeOwnPassword(request.currentPassword(), request.newPassword());
        SecurityContextHolder.clearContext();
        if (servletRequest.getSession(false) != null) {
            servletRequest.getSession(false).invalidate();
        }
    }

    @PutMapping("/{accountId}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(
            @PathVariable long accountId,
            @Valid @RequestBody ResetPasswordRequest request
    ) {
        accounts.resetPassword(accountId, request.initialPassword());
    }

    @PatchMapping("/{accountId}/status")
    public AccountView setEnabled(
            @PathVariable long accountId,
            @Valid @RequestBody AccountStatusRequest request
    ) {
        return accounts.setEnabled(accountId, request.enabled());
    }

    public record CreateAccountRequest(
            @NotBlank(message = "用户名不能为空")
            @Pattern(
                    regexp = "^[a-z0-9._-]{3,32}$",
                    message = "用户名只能包含小写字母、数字、点、下划线和连字符，长度为 3 至 32")
            String username,
            @NotBlank(message = "初始密码不能为空")
            @Size(min = PasswordRules.MIN_LENGTH, max = PasswordRules.MAX_LENGTH, message = PasswordRules.LENGTH_MESSAGE)
            String initialPassword,
            @NotNull(message = "角色不能为空") SystemRole role,
            @NotBlank(message = "显示名称不能为空")
            @Size(max = 64, message = "显示名称不能超过 64 个字符")
            String displayName
    ) {
    }

    public record UpdateProfileRequest(
            @NotBlank(message = "显示名称不能为空")
            @Size(max = 64, message = "显示名称不能超过 64 个字符")
            String displayName
    ) {
    }

    public record ChangePasswordRequest(
            @NotBlank(message = "当前密码不能为空") String currentPassword,
            @NotBlank(message = "新密码不能为空")
            @Size(min = PasswordRules.MIN_LENGTH, max = PasswordRules.MAX_LENGTH, message = PasswordRules.LENGTH_MESSAGE)
            String newPassword
    ) {
    }

    public record ResetPasswordRequest(
            @NotBlank(message = "初始密码不能为空")
            @Size(min = PasswordRules.MIN_LENGTH, max = PasswordRules.MAX_LENGTH, message = PasswordRules.LENGTH_MESSAGE)
            String initialPassword
    ) {
    }

    public record AccountStatusRequest(
            @NotNull(message = "账户状态不能为空") Boolean enabled
    ) {
    }
}
