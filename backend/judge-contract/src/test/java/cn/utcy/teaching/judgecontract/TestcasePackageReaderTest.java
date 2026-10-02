package cn.utcy.teaching.judgecontract;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestcasePackageReaderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void readsACompleteManifestAndVerifiesEveryFile() throws IOException {
        byte[] input = "1 2\n".getBytes();
        byte[] output = "3\n".getBytes();
        String manifest = manifest(Sha256.bytes(input), Sha256.bytes(output));
        Path archive = zip(Map.of(
                "manifest.json", manifest.getBytes(),
                "cases/1.in", input,
                "cases/1.out", output
        ));

        var testcasePackage = reader().read(archive, 7);

        assertThat(testcasePackage.cases()).hasSize(1);
        assertThat(testcasePackage.cases().getFirst().id()).isEqualTo("1");
        assertThat(testcasePackage.cases().getFirst().score())
                .isEqualByComparingTo("100");
    }

    @Test
    void rejectsAFileWhoseHashDoesNotMatchTheManifest() throws IOException {
        byte[] input = "1 2\n".getBytes();
        byte[] output = "4\n".getBytes();
        String manifest = manifest(Sha256.bytes(input), Sha256.bytes("3\n".getBytes()));
        Path archive = zip(Map.of(
                "manifest.json", manifest.getBytes(),
                "cases/1.in", input,
                "cases/1.out", output
        ));

        assertThatThrownBy(() -> reader().read(archive, 7))
                .isInstanceOf(TestcasePackageException.class)
                .hasMessageContaining("散列");
    }

    @Test
    void rejectsPathTraversalBeforeReadingManifestReferences() throws IOException {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("../outside", "x".getBytes());
        entries.put("manifest.json", "{}".getBytes());
        Path archive = zip(entries);

        assertThatThrownBy(() -> reader().read(archive, 7))
                .isInstanceOf(TestcasePackageException.class)
                .hasMessageContaining("越界路径");
    }

    @Test
    void rejectsActualUncompressedBytesBeyondThePackageLimit() {
        assertThatThrownBy(() -> TestcasePackageReader.addActualSize(
                TestcasePackageReader.MAX_ARCHIVE_BYTES - 1,
                1,
                1
        ))
                .isInstanceOf(TestcasePackageException.class)
                .hasMessageContaining("实际解压总大小");
    }

    private TestcasePackageReader reader() {
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return new TestcasePackageReader(mapper);
    }

    private String manifest(String inputHash, String outputHash) {
        return """
                {
                  "schemaVersion": 1,
                  "problemId": 7,
                  "cases": [
                    {
                      "id": "1",
                      "input": "cases/1.in",
                      "output": "cases/1.out",
                      "inputSha256": "%s",
                      "outputSha256": "%s",
                      "score": 100
                    }
                  ]
                }
                """.formatted(inputHash, outputHash);
    }

    private Path zip(Map<String, byte[]> entries) throws IOException {
        Path archive = Files.createTempFile(temporaryDirectory, "testcases-", ".zip");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return archive;
    }
}
