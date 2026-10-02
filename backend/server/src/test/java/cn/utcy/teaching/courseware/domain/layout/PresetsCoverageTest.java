package cn.utcy.teaching.courseware.domain.layout;

import cn.utcy.teaching.courseware.domain.Stage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 预设名单四处并存(dsl 常量/schema enum/前端标签/此处 switch),此测锁定 Java 侧全覆盖 */
class PresetsCoverageTest {

    @Test
    @DisplayName("每个 PRESET_NAMES 都有布局定义(新增预设漏改 Presets 会在此爆红)")
    void everyPresetHasLayoutDefinition() {
        for (String name : Stage.PRESET_NAMES) {
            assertThat(Presets.getPreset(name)).as(name).isNotNull();
            assertThat(Presets.getPreset(name).regions()).as(name + ".regions").isNotEmpty();
        }
    }
}
