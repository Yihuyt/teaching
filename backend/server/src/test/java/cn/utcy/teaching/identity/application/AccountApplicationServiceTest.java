package cn.utcy.teaching.identity.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.identity.domain.CredentialState;
import cn.utcy.teaching.identity.domain.UserAccount;
import cn.utcy.teaching.identity.infrastructure.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccountApplicationServiceTest {

    @Test
    void adminCannotModifyAnotherAdminProfile() {
        Fixture fixture = fixture(new Actor(1L, "admin-one", SystemRole.ADMIN));
        UserAccount target = account(2L, "admin-two", SystemRole.ADMIN);
        when(fixture.mapper.selectForUpdate(2L)).thenReturn(target);

        assertThatThrownBy(() -> fixture.service.updateProfile(2L, "新名称"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("只有 root 可以管理 admin");
    }

    @Test
    void rootCanModifyAdminProfile() {
        Fixture fixture = fixture(new Actor(1L, "root", SystemRole.ROOT));
        UserAccount target = account(2L, "admin", SystemRole.ADMIN);
        when(fixture.mapper.selectForUpdate(2L)).thenReturn(target);

        fixture.service.updateProfile(2L, "新名称");

        assertThat(target.getDisplayName()).isEqualTo("新名称");
        verify(fixture.mapper).updateById(target);
    }

    @Test
    void accountCanModifyOwnProfile() {
        Fixture fixture = fixture(new Actor(3L, "student", SystemRole.STUDENT));
        UserAccount target = account(3L, "student", SystemRole.STUDENT);
        when(fixture.mapper.selectForUpdate(3L)).thenReturn(target);

        fixture.service.updateProfile(3L, "学生");

        assertThat(target.getDisplayName()).isEqualTo("学生");
        verify(fixture.mapper).updateById(target);
    }

    @Test
    void concurrentAccountCreateReturnsExplicitUniqueConflict() {
        Fixture fixture = fixture(new Actor(1L, "root", SystemRole.ROOT));
        when(fixture.mapper.exists(any())).thenReturn(false);
        when(fixture.passwordEncoder.encode("test-password-123")).thenReturn("encoded-password");
        when(fixture.mapper.insert(any(UserAccount.class)))
                .thenThrow(new DuplicateKeyException("unique constraint"));

        assertThatThrownBy(() -> fixture.service.create(
                "teacher-one",
                "test-password-123",
                SystemRole.TEACHER,
                "教师一"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("用户名已存在");
    }

    @Test
    void accountUpdateRequiresExactlyOneAffectedRow() {
        Fixture fixture = fixture(new Actor(3L, "student", SystemRole.STUDENT));
        UserAccount target = account(3L, "student", SystemRole.STUDENT);
        when(fixture.mapper.selectForUpdate(3L)).thenReturn(target);
        when(fixture.mapper.updateById(target)).thenReturn(0);

        assertThatThrownBy(() -> fixture.service.updateProfile(3L, "学生"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("账户资料已变化，更新未生效");
    }

    private Fixture fixture(Actor actor) {
        UserAccountMapper mapper = mock(UserAccountMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(actor);
        when(mapper.updateById(any(UserAccount.class))).thenReturn(1);
        return new Fixture(
                mapper,
                passwordEncoder,
                new AccountApplicationService(
                        mapper,
                        passwordEncoder,
                        currentActor));
    }

    private UserAccount account(long id, String username, SystemRole role) {
        return new UserAccount(
                id,
                username,
                "encoded",
                CredentialState.ACTIVE,
                role,
                username,
                true,
                false,
                Instant.EPOCH,
                Instant.EPOCH);
    }

    private record Fixture(
            UserAccountMapper mapper,
            PasswordEncoder passwordEncoder,
            AccountApplicationService service
    ) {
    }
}
