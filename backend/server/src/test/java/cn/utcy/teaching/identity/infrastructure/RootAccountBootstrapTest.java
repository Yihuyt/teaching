package cn.utcy.teaching.identity.infrastructure;

import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.identity.domain.CredentialState;
import cn.utcy.teaching.identity.domain.UserAccount;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RootAccountBootstrapTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void rootBootstrapIsEnabledByDefault() {
        rootBootstrapContext()
                .run(context -> assertThat(context)
                        .hasSingleBean(RootAccountBootstrap.class));
    }

    @Test
    void rootBootstrapIsDisabledOnlyByExplicitProperty() {
        rootBootstrapContext()
                .withPropertyValues("teaching.bootstrap.root-account-enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(RootAccountBootstrap.class));
    }

    @Test
    void createsRootFromStrictPasswordFileWhenDatabaseHasNoRoot() throws Exception {
        Path passwordFile = temporaryDirectory.resolve("root-password");
        Files.writeString(passwordFile, "test-password-123");
        UserAccountMapper mapper = mock(UserAccountMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(mapper.insert(any(UserAccount.class))).thenReturn(1);
        when(encoder.encode("test-password-123")).thenReturn("encoded-password");

        bootstrap(mapper, encoder, passwordFile).run(new DefaultApplicationArguments());

        ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
        verify(mapper).insert(captor.capture());
        UserAccount root = captor.getValue();
        assertThat(root.getUsername()).isEqualTo("root");
        assertThat(root.getRole()).isEqualTo(SystemRole.ROOT);
        assertThat(root.getCredentialState()).isEqualTo(CredentialState.ACTIVE);
        assertThat(root.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(root.isMustResetPassword()).isTrue();
    }

    @Test
    void activatesMigratedRootThatRequiresPasswordReset() throws Exception {
        Path passwordFile = temporaryDirectory.resolve("root-password");
        Files.writeString(passwordFile, "test-password-123");
        UserAccountMapper mapper = mock(UserAccountMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        UserAccount root = new UserAccount(
                1L,
                "root",
                null,
                CredentialState.RESET_REQUIRED,
                SystemRole.ROOT,
                "系统管理员",
                true,
                true,
                Instant.EPOCH,
                Instant.EPOCH);
        when(mapper.selectList(any())).thenReturn(List.of(root));
        when(mapper.updateById(root)).thenReturn(1);
        when(encoder.encode("test-password-123")).thenReturn("encoded-password");

        bootstrap(mapper, encoder, passwordFile).run(new DefaultApplicationArguments());

        assertThat(root.getCredentialState()).isEqualTo(CredentialState.ACTIVE);
        assertThat(root.getPasswordHash()).isEqualTo("encoded-password");
        assertThat(root.isMustResetPassword()).isTrue();
        verify(mapper).updateById(root);
    }

    @Test
    void leavesActiveRootUnchangedWithoutReadingPasswordFile() {
        Path missingPasswordFile = temporaryDirectory.resolve("missing");
        UserAccountMapper mapper = mock(UserAccountMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        UserAccount root = new UserAccount(
                1L,
                "root",
                "encoded-password",
                CredentialState.ACTIVE,
                SystemRole.ROOT,
                "系统管理员",
                true,
                false,
                Instant.EPOCH,
                Instant.EPOCH);
        when(mapper.selectList(any())).thenReturn(List.of(root));

        bootstrap(mapper, encoder, missingPasswordFile).run(new DefaultApplicationArguments());

        verify(encoder, never()).encode(any());
        verify(mapper, never()).insert(any(UserAccount.class));
        verify(mapper, never()).updateById(any(UserAccount.class));
    }

    @Test
    void rejectsPasswordFileWithMoreThanOneLine() throws Exception {
        Path passwordFile = temporaryDirectory.resolve("root-password");
        Files.writeString(passwordFile, "test-password-123\nunexpected");
        UserAccountMapper mapper = mock(UserAccountMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> bootstrap(mapper, encoder, passwordFile)
                        .run(new DefaultApplicationArguments()))
                .withMessage("root 初始密码文件必须严格包含一行 6 至 128 个字符的密码");
    }

    @Test
    void rejectsPasswordFileWithTrailingNewline() throws Exception {
        Path passwordFile = temporaryDirectory.resolve("root-password");
        Files.writeString(passwordFile, "test-password-123\n");
        UserAccountMapper mapper = mock(UserAccountMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> bootstrap(mapper, mock(PasswordEncoder.class), passwordFile)
                        .run(new DefaultApplicationArguments()))
                .withMessage("root 初始密码文件必须严格包含一行 6 至 128 个字符的密码");
    }

    @Test
    void rejectsSymbolicLinkPasswordPath() throws Exception {
        Path target = temporaryDirectory.resolve("password-target");
        Files.writeString(target, "test-password-123");
        Path passwordLink = temporaryDirectory.resolve("root-password");
        Files.createSymbolicLink(passwordLink, target);
        UserAccountMapper mapper = mock(UserAccountMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> bootstrap(mapper, mock(PasswordEncoder.class), passwordLink)
                        .run(new DefaultApplicationArguments()))
                .withMessage("root 初始密码路径必须是非符号链接的普通文件");
    }

    @Test
    void rejectsDirectoryPasswordPath() {
        UserAccountMapper mapper = mock(UserAccountMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> bootstrap(
                                mapper,
                                mock(PasswordEncoder.class),
                                temporaryDirectory)
                        .run(new DefaultApplicationArguments()))
                        .withMessage("root 初始密码路径必须是非符号链接的普通文件");
    }

    @Test
    void reportsConcurrentRootCreationAsExplicitInvariantViolation() throws Exception {
        Path passwordFile = temporaryDirectory.resolve("root-password");
        Files.writeString(passwordFile, "test-password-123");
        UserAccountMapper mapper = mock(UserAccountMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(encoder.encode("test-password-123")).thenReturn("encoded-password");
        when(mapper.insert(any(UserAccount.class)))
                .thenThrow(new DuplicateKeyException("unique constraint"));

        assertThatIllegalStateException()
                .isThrownBy(() -> bootstrap(mapper, encoder, passwordFile)
                        .run(new DefaultApplicationArguments()))
                .withMessage("root 账户创建冲突，数据库必须且只能存在一个 root 账户");
    }

    @Test
    void requiresRootCreationToAffectExactlyOneRow() throws Exception {
        Path passwordFile = temporaryDirectory.resolve("root-password");
        Files.writeString(passwordFile, "test-password-123");
        UserAccountMapper mapper = mock(UserAccountMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        when(encoder.encode("test-password-123")).thenReturn("encoded-password");
        when(mapper.insert(any(UserAccount.class))).thenReturn(0);

        assertThatIllegalStateException()
                .isThrownBy(() -> bootstrap(mapper, encoder, passwordFile)
                        .run(new DefaultApplicationArguments()))
                .withMessage("root 账户创建未生效");
    }

    private RootAccountBootstrap bootstrap(
            UserAccountMapper mapper,
            PasswordEncoder encoder,
            Path passwordFile
    ) {
        return new RootAccountBootstrap(mapper, encoder, new BootstrapProperties(passwordFile));
    }

    private ApplicationContextRunner rootBootstrapContext() {
        return new ApplicationContextRunner()
                .withBean(UserAccountMapper.class, () -> mock(UserAccountMapper.class))
                .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
                .withBean(
                        BootstrapProperties.class,
                        () -> new BootstrapProperties(temporaryDirectory.resolve("root-password")))
                .withUserConfiguration(RootAccountBootstrap.class);
    }
}
