package cn.utcy.teaching.course.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 数据库不设外键:建表脚本不得有外键约束,引用完整性由应用层(各模块的删除守卫 / 清理组件)负责 */
class NoForeignKeySchemaContractTest {

    @Test
    void schemaHasNoForeignKeys() throws IOException {
        try (var input = getClass().getResourceAsStream("/db/schema.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
            assertThat(sql).contains("CREATE TABLE").doesNotContain("FOREIGN KEY").doesNotContain("REFERENCES ");
        }
    }
}
