package cn.utcy.teaching.identity.infrastructure;

import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.identity.domain.CredentialState;
import cn.utcy.teaching.identity.domain.PasswordRules;
import cn.utcy.teaching.identity.domain.UserAccount;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.List;

@Component
@ConditionalOnProperty(
        prefix = "teaching.bootstrap",
        name = "root-account-enabled",
        havingValue = "true",
        matchIfMissing = true)
@Order(Ordered.HIGHEST_PRECEDENCE)
class RootAccountBootstrap implements ApplicationRunner {

    private final UserAccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;
    private final BootstrapProperties properties;

    RootAccountBootstrap(
            UserAccountMapper accountMapper,
            PasswordEncoder passwordEncoder,
            BootstrapProperties properties
    ) {
        this.accountMapper = accountMapper;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        List<UserAccount> roots = accountMapper.selectList(
                new LambdaQueryWrapper<UserAccount>().eq(UserAccount::getRole, SystemRole.ROOT));
        if (roots.size() > 1) {
            throw new IllegalStateException("数据库中存在多个 root 账户，必须先修正账户数据");
        }

        if (roots.size() == 1 && roots.getFirst().getCredentialState() == CredentialState.ACTIVE) {
            return;
        }

        String initialPassword = readInitialPassword();
        String encodedPassword = passwordEncoder.encode(initialPassword);
        if (roots.isEmpty()) {
            UserAccount root = UserAccount.create(
                    "root",
                    encodedPassword,
                    SystemRole.ROOT,
                    "系统管理员",
                    true);
            try {
                requireSingleMutation(
                        accountMapper.insert(root),
                        "root 账户创建未生效");
            } catch (DuplicateKeyException exception) {
                throw new IllegalStateException(
                        "root 账户创建冲突，数据库必须且只能存在一个 root 账户",
                        exception);
            }
            return;
        }

        UserAccount root = roots.getFirst();
        root.changePassword(encodedPassword, true);
        root.setEnabled(true);
        requireSingleMutation(
                accountMapper.updateById(root),
                "root 账户初始化未生效");
    }

    private String readInitialPassword() {
        if (!Files.isRegularFile(properties.rootPasswordPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("root 初始密码路径必须是非符号链接的普通文件");
        }
        String password;
        try {
            password = Files.readString(properties.rootPasswordPath(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取 root 初始密码文件", exception);
        }
        if (password.contains("\n")
                || password.contains("\r")
                || password.length() < PasswordRules.MIN_LENGTH
                || password.length() > 128) {
            throw new IllegalStateException("root 初始密码文件必须严格包含一行 " + PasswordRules.MIN_LENGTH + " 至 " + PasswordRules.MAX_LENGTH + " 个字符的密码");
        }
        return password;
    }

    private void requireSingleMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new IllegalStateException(message);
        }
    }
}
