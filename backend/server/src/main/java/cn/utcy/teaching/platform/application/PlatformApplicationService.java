package cn.utcy.teaching.platform.application;

import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.storage.ObjectStorageStatusProvider;
import cn.utcy.teaching.shared.storage.ObjectStorageStatusProvider.ObjectStorageStatus;
import cn.utcy.teaching.shared.actor.OwnershipPolicy;
import cn.utcy.teaching.platform.domain.PlatformSetting;
import cn.utcy.teaching.platform.infrastructure.PlatformSettingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class PlatformApplicationService {

    private static final long SETTINGS_ID = 1L;

    private final PlatformSettingMapper settings;
    private final CurrentActor currentActor;
    private final OwnershipPolicy ownership;
    private final ObjectStorageStatusProvider objectStorage;

    public PlatformApplicationService(
            PlatformSettingMapper settings,
            CurrentActor currentActor,
            OwnershipPolicy ownership,
            ObjectStorageStatusProvider objectStorage
    ) {
        this.settings = settings;
        this.currentActor = currentActor;
        this.ownership = ownership;
        this.objectStorage = objectStorage;
    }

    @Transactional(readOnly = true)
    public PlatformView getPublicSettings() {
        return view(requireSettings());
    }

    @Transactional(readOnly = true)
    public PlatformView getSettings() {
        ownership.requireRoot(currentActor.require());
        return view(requireSettings());
    }

    @Transactional
    public PlatformView update(String siteName, String footerText) {
        ownership.requireRoot(currentActor.require());
        PlatformSetting setting = settings.selectForUpdate(SETTINGS_ID);
        if (setting == null) {
            throw new NotFoundException("平台设置尚未初始化");
        }
        setting.update(siteName.trim(), footerText.trim());
        if (settings.updateById(setting) != 1) {
            throw new ConflictException("平台设置状态已变化，更新未生效");
        }
        return view(setting);
    }

    public ObjectStorageStatus checkObjectStorage() {
        ownership.requireRoot(currentActor.require());
        return objectStorage.check();
    }

    public record RetriedDeletions(long retried) {
    }

    public RetriedDeletions retryFailedDeletions() {
        ownership.requireRoot(currentActor.require());
        return new RetriedDeletions(objectStorage.retryFailedDeletions());
    }

    private PlatformSetting requireSettings() {
        PlatformSetting setting = settings.selectById(SETTINGS_ID);
        if (setting == null) {
            throw new NotFoundException("平台设置尚未初始化");
        }
        return setting;
    }

    private PlatformView view(PlatformSetting setting) {
        return new PlatformView(
                setting.getSiteName(),
                setting.getFooterText(),
                setting.getUpdatedAt());
    }

    public record PlatformView(
            String siteName,
            String footerText,
            Instant updatedAt
    ) {
    }
}
