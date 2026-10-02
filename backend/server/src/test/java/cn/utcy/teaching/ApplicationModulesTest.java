package cn.utcy.teaching;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ApplicationModulesTest {

    @Test
    void moduleBoundariesAreValid() {
        ApplicationModules.of(TeachingApplication.class).verify();
    }
}
