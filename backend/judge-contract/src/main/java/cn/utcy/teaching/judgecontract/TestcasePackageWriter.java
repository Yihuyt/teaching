package cn.utcy.teaching.judgecontract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.zip.Zip64Mode;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class TestcasePackageWriter {

    private static final Pattern CASE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int SCORE_HUNDREDTHS = 10_000;

    private final ObjectMapper objectMapper;

    public TestcasePackageWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(Path archive, long problemId, List<TestcaseSource> sources) {
        validate(problemId, sources);
        List<TestcaseManifest.TestcaseDefinition> definitions =
                definitions(sources);
        TestcaseManifest manifest = new TestcaseManifest(
                1, problemId, definitions);

        try (OutputStream file = Files.newOutputStream(archive);
             ZipArchiveOutputStream zip = new ZipArchiveOutputStream(file)) {
            zip.setUseZip64(Zip64Mode.AsNeeded);
            writeEntry(zip, "manifest.json", objectMapper.writeValueAsBytes(manifest));
            for (int index = 0; index < sources.size(); index++) {
                TestcaseSource source = sources.get(index);
                TestcaseManifest.TestcaseDefinition definition = definitions.get(index);
                writeEntry(zip, definition.input(), source.input());
                writeEntry(zip, definition.output(), source.output());
            }
            zip.finish();
        } catch (IOException exception) {
            throw new TestcasePackageException("无法生成测试包", exception);
        }
        if (fileSize(archive) > TestcasePackageReader.MAX_ARCHIVE_BYTES) {
            throw new TestcasePackageException("生成的测试包大小超过限制");
        }
    }

    private void validate(long problemId, List<TestcaseSource> sources) {
        if (problemId < 1) {
            throw new TestcasePackageException("测试包 problemId 必须大于零");
        }
        if (sources == null
                || sources.isEmpty()
                || sources.size() > TestcasePackageReader.MAX_CASES) {
            throw new TestcasePackageException(
                    "测试点数量必须在 1 到 "
                            + TestcasePackageReader.MAX_CASES
                            + " 之间");
        }
        Set<String> ids = new HashSet<>();
        long totalBytes = 0;
        for (TestcaseSource source : sources) {
            if (source == null
                    || source.id() == null
                    || !CASE_ID.matcher(source.id()).matches()
                    || !ids.add(source.id())) {
                throw new TestcasePackageException("测试点 ID 非法或重复");
            }
            if (source.input() == null || source.output() == null) {
                throw new TestcasePackageException(
                        "测试点输入和期望输出不能为空：" + source.id());
            }
            if (source.input().length > TestcasePackageReader.MAX_ENTRY_BYTES
                    || source.output().length > TestcasePackageReader.MAX_ENTRY_BYTES) {
                throw new TestcasePackageException(
                        "测试点文件大小超过限制：" + source.id());
            }
            try {
                totalBytes = Math.addExact(
                        totalBytes,
                        Math.addExact(
                                (long) source.input().length,
                                source.output().length));
            } catch (ArithmeticException exception) {
                throw new TestcasePackageException("测试点总大小超过限制", exception);
            }
            if (totalBytes > TestcasePackageReader.MAX_ARCHIVE_BYTES) {
                throw new TestcasePackageException("测试点总大小超过限制");
            }
        }
    }

    private List<TestcaseManifest.TestcaseDefinition> definitions(
            List<TestcaseSource> sources
    ) {
        int base = SCORE_HUNDREDTHS / sources.size();
        int remainder = SCORE_HUNDREDTHS % sources.size();
        List<TestcaseManifest.TestcaseDefinition> definitions =
                new ArrayList<>(sources.size());
        for (int index = 0; index < sources.size(); index++) {
            TestcaseSource source = sources.get(index);
            String inputPath = "cases/" + source.id() + ".in";
            String outputPath = "cases/" + source.id() + ".out";
            int scoreHundredths = base + (index < remainder ? 1 : 0);
            BigDecimal score = BigDecimal.valueOf(scoreHundredths, 2)
                    .setScale(2, RoundingMode.UNNECESSARY);
            definitions.add(new TestcaseManifest.TestcaseDefinition(
                    source.id(),
                    inputPath,
                    outputPath,
                    Sha256.bytes(source.input()),
                    Sha256.bytes(source.output()),
                    score));
        }
        return List.copyOf(definitions);
    }

    private void writeEntry(
            ZipArchiveOutputStream zip,
            String name,
            byte[] content
    ) throws IOException {
        ZipArchiveEntry entry = new ZipArchiveEntry(name);
        entry.setTime(0);
        entry.setSize(content.length);
        zip.putArchiveEntry(entry);
        zip.write(content);
        zip.closeArchiveEntry();
    }

    private long fileSize(Path archive) {
        try {
            return Files.size(archive);
        } catch (IOException exception) {
            throw new TestcasePackageException(
                    "无法读取生成后的测试包大小", exception);
        }
    }

    public record TestcaseSource(String id, byte[] input, byte[] output) {
    }
}
