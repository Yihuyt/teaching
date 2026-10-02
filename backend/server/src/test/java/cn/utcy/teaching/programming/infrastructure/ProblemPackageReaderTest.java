package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProblemPackageReaderTest {

    @TempDir
    Path temporaryDirectory;

    private final ProblemPackageReader reader =
            new ProblemPackageReader(new ObjectMapper());

    @Test
    void readsTheExplicitChinesePassFailProfile() throws IOException {
        Path archive = archive(validEntries());

        ProblemPackageReader.ParsedProblemPackage result =
                reader.read(archive, "sampleproblem.zip");

        assertThat(result.title()).isEqualTo("两数之和");
        assertThat(result.timeLimitMs()).isEqualTo(1_500);
        assertThat(result.memoryLimitMb()).isEqualTo(256);
        assertThat(result.outputLimitKb()).isEqualTo(8 * 1_024);
        assertThat(result.languages()).containsExactlyInAnyOrder(
                ProgrammingLanguage.C17,
                ProgrammingLanguage.CPP20,
                ProgrammingLanguage.PYTHON312);
        assertThat(result.samples()).hasSize(1);
        assertThat(result.secretCases()).hasSize(2);
        assertThat(result.sourcesJson()).contains("课程示例").contains("\"url\":");
        assertThat(result.creditsJson())
                .contains("\"name\":\"出题教师\"")
                .contains("\"email\":\"teacher@example.edu\"");
    }

    @Test
    void rejectsRuntimeFeaturesThePlatformDoesNotExecute() throws IOException {
        Map<String, String> entries = validEntries();
        entries.put(
                "sampleproblem/output_validator/check.cpp",
                "int main() { return 0; }\n");
        Path archive = archive(entries);

        assertThatThrownBy(() -> reader.read(archive, "sampleproblem.zip"))
                .isInstanceOf(ProblemPackageException.class)
                .hasMessageContaining("不支持条目")
                .hasMessageContaining("output_validator");
    }

    @Test
    void rejectsMissingExplicitLimitsInsteadOfGuessingDefaults()
            throws IOException {
        Map<String, String> entries = validEntries();
        entries.put(
                "sampleproblem/problem.yaml",
                """
                problem_format_version: 2025-09
                type: pass-fail
                name:
                  zh: 两数之和
                uuid: 7594abe6-08e3-4743-8cf9-15c4693cdbf5
                license: unknown
                limits:
                  time_limit: 1
                  memory: 256
                languages: [cpp]
                """);
        Path archive = archive(entries);

        assertThatThrownBy(() -> reader.read(archive, "sampleproblem.zip"))
                .isInstanceOf(ProblemPackageException.class)
                .hasMessage("limits 必须显式包含 time_limit、memory 和 output");
    }

    @Test
    void rejectsArchiveWhoseTopDirectoryDoesNotMatchFilename()
            throws IOException {
        Path archive = archive(validEntries());

        assertThatThrownBy(() -> reader.read(archive, "another.zip"))
                .isInstanceOf(ProblemPackageException.class)
                .hasMessageContaining("不匹配短名称");
    }

    private Map<String, String> validEntries() {
        Map<String, String> entries = new LinkedHashMap<>();
        entries.put(
                "sampleproblem/problem.yaml",
                """
                problem_format_version: 2025-09
                type: pass-fail
                name:
                  zh: 两数之和
                uuid: 7594abe6-08e3-4743-8cf9-15c4693cdbf5
                version: "1.0"
                credits:
                  authors:
                    - 出题教师 <teacher@example.edu>
                source:
                  name: 课程示例
                  url: https://example.edu/problems
                license: cc by
                rights_owner: 示例大学
                limits:
                  time_limit: 1.5
                  memory: 256
                  output: 8
                languages: [c, cpp, python3]
                allow_file_writing: false
                """);
        entries.put(
                "sampleproblem/statement/problem.zh.md",
                "# 两数之和\n\n输入两个整数，输出它们的和。\n");
        entries.put("sampleproblem/data/sample/1.in", "1 2\n");
        entries.put("sampleproblem/data/sample/1.ans", "3\n");
        entries.put("sampleproblem/data/secret/01.in", "5 8\n");
        entries.put("sampleproblem/data/secret/01.ans", "13\n");
        entries.put("sampleproblem/data/secret/02.in", "-1 1\n");
        entries.put("sampleproblem/data/secret/02.ans", "0\n");
        entries.put(
                "sampleproblem/submissions/accepted/solution.cpp",
                "int main() { return 0; }\n");
        entries.put(
                "sampleproblem/input_validators/validator.cpp",
                "int main() { return 0; }\n");
        return entries;
    }

    private Path archive(Map<String, String> entries) throws IOException {
        Path archive = temporaryDirectory.resolve("sampleproblem.zip");
        try (ZipArchiveOutputStream zip =
                     new ZipArchiveOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, String> value : entries.entrySet()) {
                ZipArchiveEntry entry = new ZipArchiveEntry(value.getKey());
                byte[] content = value.getValue().getBytes(StandardCharsets.UTF_8);
                entry.setSize(content.length);
                zip.putArchiveEntry(entry);
                zip.write(content);
                zip.closeArchiveEntry();
            }
            zip.finish();
        }
        return archive;
    }
}
