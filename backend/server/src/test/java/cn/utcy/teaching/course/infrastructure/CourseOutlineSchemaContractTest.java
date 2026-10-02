package cn.utcy.teaching.course.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CourseOutlineSchemaContractTest {

    @Test
    void questionPaperHasItemTable() throws IOException {
        assertThat(baseline()).contains("CREATE TABLE `course_question_item` (");
    }

    @Test
    void materialNamesAreUniqueWithinAFolder() throws IOException {
        assertThat(baseline())
                .contains("CONSTRAINT `ck_course_material_parent_scope` CHECK ((`parent_scope` = coalesce(`parent_id`,0)))")
                .contains("UNIQUE KEY `uk_course_material_name` (`course_id`,`parent_scope`,`name`)");
    }

    private String baseline() throws IOException {
        try (var input = getClass().getResourceAsStream("/db/schema.sql")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim();
        }
    }
}
