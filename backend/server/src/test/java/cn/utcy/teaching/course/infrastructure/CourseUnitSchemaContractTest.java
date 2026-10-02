package cn.utcy.teaching.course.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** 单元只是组织手段,没有自己的摘要正文 */
class CourseUnitSchemaContractTest {

    @Test
    void unitHasNoSummaryColumn() throws IOException {
        try (var input = getClass().getResourceAsStream("/db/schema.sql")) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(sql).contains("CREATE TABLE `course_unit` (").doesNotContain("summary_markdown");
        }
    }
}
