package cn.utcy.teaching.identity.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.web.PageResponse;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.identity.domain.PasswordRules;
import cn.utcy.teaching.identity.domain.UserAccount;
import cn.utcy.teaching.identity.infrastructure.UserAccountMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

@Service
public class AccountApplicationService implements AccountDirectory {

    private final UserAccountMapper mapper;
    private final PasswordEncoder passwordEncoder;
    private final CurrentActor currentActor;

    public AccountApplicationService(
            UserAccountMapper mapper,
            PasswordEncoder passwordEncoder,
            CurrentActor currentActor
    ) {
        this.mapper = mapper;
        this.passwordEncoder = passwordEncoder;
        this.currentActor = currentActor;
    }

    @Transactional(readOnly = true)
    public AccountView currentAccount() {
        return view(requireAccount(currentActor.require().userId()));
    }

    @Override
    @Transactional(readOnly = true)
    public AccountSummary require(long accountId) {
        UserAccount account = requireAccount(accountId);
        return new AccountSummary(
                account.getId(),
                account.getUsername(),
                account.getDisplayName(),
                account.getRole(),
                account.isEnabled());
    }

    @Override
    @Transactional(readOnly = true)
    public AccountSummary requireByUsername(String username) {
        return findByUsername(username).orElseThrow(() -> new NotFoundException("账户不存在"));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountSummary> findByUsername(String username) {
        String normalized = normalizeUsername(username);
        UserAccount account = mapper.selectOne(new LambdaQueryWrapper<UserAccount>()
                .eq(UserAccount::getUsername, normalized));
        if (account == null) {
            return Optional.empty();
        }
        return Optional.of(new AccountSummary(
                account.getId(),
                account.getUsername(),
                account.getDisplayName(),
                account.getRole(),
                account.isEnabled()));
    }

    @Transactional(readOnly = true)
    public PageResponse<AccountView> list(int page, int size, SystemRole role, String keyword) {
        requireAdministrator();
        LambdaQueryWrapper<UserAccount> query = new LambdaQueryWrapper<UserAccount>()
                .eq(role != null, UserAccount::getRole, role)
                .and(keyword != null && !keyword.isBlank(), condition -> condition
                        .like(UserAccount::getUsername, keyword)
                        .or()
                        .like(UserAccount::getDisplayName, keyword))
                .orderByDesc(UserAccount::getCreatedAt);
        Page<UserAccount> result = mapper.selectPage(Page.of(page, size), query);
        return PageResponse.of(result.getRecords().stream().map(this::view).toList(),
                result.getTotal(), page, size);
    }

    @Transactional
    public AccountView create(
            String username,
            String initialPassword,
            SystemRole role,
            String displayName
    ) {
        Actor actor = requireAdministrator();
        requireAssignableRole(actor, role);
        validatePassword(initialPassword);
        String normalized = normalizeUsername(username);
        if (mapper.exists(new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getUsername, normalized))) {
            throw new ConflictException("用户名已存在");
        }
        UserAccount account = UserAccount.create(
                normalized,
                passwordEncoder.encode(initialPassword),
                role,
                displayName.trim(),
                false);
        try {
            requireSingleMutation(
                    mapper.insert(account),
                    "账户创建未生效");
        } catch (DuplicateKeyException exception) {
            throw new ConflictException("用户名已存在");
        }
        return view(account);
    }

    @Transactional
    public AccountView updateProfile(long accountId, String displayName) {
        Actor actor = currentActor.require();
        UserAccount account = requireLockedAccount(accountId);
        if (actor.userId() != accountId) {
            if (!actor.role().isPlatformAdministrator()) {
                throw new ForbiddenOperationException("只能修改自己的资料");
            }
            requireManageableAccount(actor, account);
        }
        account.changeProfile(displayName.trim());
        requireSingleMutation(
                mapper.updateById(account),
                "账户资料已变化，更新未生效");
        return view(account);
    }

    @Transactional
    public void changeOwnPassword(String currentPassword, String newPassword) {
        Actor actor = currentActor.require();
        UserAccount account = requireLockedAccount(actor.userId());
        if (!passwordEncoder.matches(currentPassword, account.getPasswordHash())) {
            throw new BadRequestException("当前密码不正确");
        }
        validatePassword(newPassword);
        account.changePassword(passwordEncoder.encode(newPassword), false);
        requireSingleMutation(
                mapper.updateById(account),
                "账户密码已变化，更新未生效");
    }

    @Transactional
    public void resetPassword(long accountId, String initialPassword) {
        Actor actor = requireAdministrator();
        UserAccount account = requireLockedAccount(accountId);
        requireManageableAccount(actor, account);
        validatePassword(initialPassword);
        account.changePassword(passwordEncoder.encode(initialPassword), false);
        requireSingleMutation(
                mapper.updateById(account),
                "账户密码已变化，重置未生效");
    }

    @Transactional
    public AccountView setEnabled(long accountId, boolean enabled) {
        Actor actor = requireAdministrator();
        UserAccount account = requireLockedAccount(accountId);
        requireManageableAccount(actor, account);
        if (account.getId() == actor.userId() && !enabled) {
            throw new BadRequestException("不能停用当前登录账户");
        }
        account.setEnabled(enabled);
        requireSingleMutation(
                mapper.updateById(account),
                "账户状态已变化，更新未生效");
        return view(account);
    }

    private Actor requireAdministrator() {
        Actor actor = currentActor.require();
        if (!actor.role().isPlatformAdministrator()) {
            throw new ForbiddenOperationException("此操作仅限平台管理员");
        }
        return actor;
    }

    private void requireAssignableRole(Actor actor, SystemRole role) {
        if (role == SystemRole.ROOT) {
            throw new ForbiddenOperationException("系统只允许一个 root 账户");
        }
        if (actor.role() == SystemRole.ADMIN && role == SystemRole.ADMIN) {
            throw new ForbiddenOperationException("只有 root 可以创建 admin");
        }
    }

    private void requireManageableAccount(Actor actor, UserAccount account) {
        if (account.getRole() == SystemRole.ROOT) {
            throw new ForbiddenOperationException("root 账户不能由此接口管理");
        }
        if (actor.role() == SystemRole.ADMIN && account.getRole() == SystemRole.ADMIN) {
            throw new ForbiddenOperationException("只有 root 可以管理 admin");
        }
    }

    private UserAccount requireAccount(long id) {
        UserAccount account = mapper.selectById(id);
        if (account == null) {
            throw new NotFoundException("账户不存在");
        }
        return account;
    }

    private UserAccount requireLockedAccount(long id) {
        UserAccount account = mapper.selectForUpdate(id);
        if (account == null) {
            throw new NotFoundException("账户不存在");
        }
        return account;
    }

    private void requireSingleMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new ConflictException(message);
        }
    }

    private AccountView view(UserAccount account) {
        return new AccountView(
                account.getId(),
                account.getUsername(),
                account.getRole(),
                account.getCredentialState(),
                account.getDisplayName(),
                account.isEnabled(),
                account.isMustResetPassword(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }

    private void validatePassword(String password) {
        if (!PasswordRules.lengthOk(password)) {
            throw new BadRequestException(PasswordRules.LENGTH_MESSAGE);
        }
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new BadRequestException("用户名不能为空");
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
