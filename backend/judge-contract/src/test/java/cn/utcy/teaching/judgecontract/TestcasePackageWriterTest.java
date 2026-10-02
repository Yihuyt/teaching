package cn.utcy.teaching.judgecontract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestcasePackageWriterTest {

    @TempDir
    Path temporaryDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final TestcasePackageWriter writer =
            new TestcasePackageWriter(objectMapper);
    private final TestcasePackageReader reader =
            new TestcasePackageReader(objectMapper);

    @Test
    void writesPackageAcceptedByTheSharedReader() {
        Path archive = temporaryDirectory.resolve("cases.zip");
        writer.write(archive, 42, List.of(
                source("one", "1 2\n", "3\n"),
                source("two", "5 8\n", "13\n"),
                source("three", "0 0\n", "0\n")));

        TestcasePackage result = reader.read(archive, 42);

        assertThat(result.cases()).hasSize(3);
        assertThat(result.cases())
                .extracting(TestcasePackage.Testcase::score)
                .containsExactly(
                        new java.math.BigDecimal("33.34"),
                        new java.math.BigDecimal("33.33"),
                        new java.math.BigDecimal("33.33"));
    }

    @Test
    void rejectsDuplicateCaseIds() {
        Path archive = temporaryDirectory.resolve("cases.zip");

        assertThatThrownBy(() -> writer.write(archive, 42, List.of(
                source("same", "1\n", "1\n"),
                source("same", "2\n", "2\n"))))
                .isInstanceOf(TestcasePackageException.class)
                .hasMessage("测试点 ID 非法或重复");
    }

    private TestcasePackageWriter.TestcaseSource source(
            String id,
            String input,
            String output
    ) {
        return new TestcasePackageWriter.TestcaseSource(
                id,
                input.getBytes(StandardCharsets.UTF_8),
                output.getBytes(StandardCharsets.UTF_8));
    }
}
