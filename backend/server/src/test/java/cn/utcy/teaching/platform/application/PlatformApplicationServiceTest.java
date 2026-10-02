package cn.utcy.teaching.platform.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ForbiddenOperationException;
import cn.utcy.teaching.shared.storage.ObjectStorageStatusProvider;
import cn.utcy.teaching.shared.actor.OwnershipPolicy;
import cn.utcy.teaching.shared.actor.SystemRole;
import cn.utcy.teaching.platform.infrastructure.PlatformSettingMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformApplicationServiceTest {

    @Test
    void adminCannotReadManagementSettings() {
        PlatformApplicationService service = serviceFor(SystemRole.ADMIN);

        assertThatThrownBy(service::getSettings)
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("此操作仅限 root");
    }

    @Test
    void adminCannotUpdatePlatformSettings() {
        PlatformApplicationService service = serviceFor(SystemRole.ADMIN);

        assertThatThrownBy(() -> service.update("平台", "页脚"))
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("此操作仅限 root");
    }

    @Test
    void adminCannotReadObjectStorageStatus() {
        PlatformApplicationService service = serviceFor(SystemRole.ADMIN);

        assertThatThrownBy(service::checkObjectStorage)
                .isInstanceOf(ForbiddenOperationException.class)
                .hasMessage("此操作仅限 root");
    }

    private PlatformApplicationService serviceFor(SystemRole role) {
        CurrentActor currentActor = mock(CurrentActor.class);
        when(currentActor.require()).thenReturn(new Actor(9L, "admin", role));
        return new PlatformApplicationService(
                mock(PlatformSettingMapper.class),
                currentActor,
                new OwnershipPolicy(),
                mock(ObjectStorageStatusProvider.class));
    }
}
