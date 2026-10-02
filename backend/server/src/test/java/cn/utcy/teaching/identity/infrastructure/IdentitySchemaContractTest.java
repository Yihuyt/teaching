package cn.utcy.teaching.identity.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class IdentitySchemaContractTest {

    @Test
    void databaseEnforcesAtMostOneRootAccount() throws IOException {
        try (InputStream migration = getClass().getResourceAsStream("/db/schema.sql")) {
            assertThat(migration).isNotNull();
            String sql = new String(migration.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
            assertThat(sql).contains(
                    "UNIQUE KEY `uk_user_account_single_root` (((case when (`role` = _utf8mb4'root') then 1 else NULL end)))");
        }
    }
}
