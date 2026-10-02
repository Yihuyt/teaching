package cn.utcy.teaching.judgecontract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class TestcasePackageReader {

    public static final long MAX_ARCHIVE_BYTES = 256L * 1024 * 1024;
    public static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;
    public static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    public static final int MAX_CASES = 500;
    private static final int MAX_ENTRIES = MAX_CASES * 2 + 1;
    private static final Pattern CASE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final BigDecimal TOTAL_SCORE = new BigDecimal("100");

    private final ObjectMapper objectMapper;

    public TestcasePackageReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TestcasePackage read(Path archive, long expectedProblemId) {
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            Map<String, ZipArchiveEntry> entries = inspectEntries(zip);
            ZipArchiveEntry manifestEntry = entries.get("manifest.json");
            if (manifestEntry == null || manifestEntry.getSize() > MAX_MANIFEST_BYTES) {
                throw new TestcasePackageException("测试包缺少合法的 manifest.json");
            }
            byte[] manifestBytes = read(zip, manifestEntry, MAX_MANIFEST_BYTES);
            long actualTotalSize = manifestBytes.length;
            TestcaseManifest manifest = parseManifest(manifestBytes);
            validateManifest(manifest, expectedProblemId);

            Set<String> referencedEntries = new HashSet<>();
            referencedEntries.add("manifest.json");
            List<TestcasePackage.Testcase> cases = new ArrayList<>(manifest.cases().size());
            Set<String> caseIds = new HashSet<>();
            BigDecimal score = BigDecimal.ZERO;

            for (TestcaseManifest.TestcaseDefinition definition : manifest.cases()) {
                validateDefinition(definition, caseIds, referencedEntries);
                byte[] input = readRequired(zip, entries, definition.input());
                byte[] output = readRequired(zip, entries, definition.output());
                actualTotalSize = addActualSize(actualTotalSize, input.length, output.length);
                if (!Sha256.bytes(input).equals(definition.inputSha256())
                        || !Sha256.bytes(output).equals(definition.outputSha256())) {
                    throw new TestcasePackageException(
                            "测试点文件散列与 manifest 不一致：" + definition.id());
                }
                score = score.add(definition.score());
                cases.add(new TestcasePackage.Testcase(
                        definition.id(), input, output, definition.score()));
            }

            if (score.compareTo(TOTAL_SCORE) != 0) {
                throw new TestcasePackageException("测试点总分必须严格等于 100");
            }
            if (!entries.keySet().equals(referencedEntries)) {
                throw new TestcasePackageException("测试包包含 manifest 未引用的文件");
            }
            return new TestcasePackage(List.copyOf(cases));
        } catch (TestcasePackageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new TestcasePackageException("无法读取测试包", exception);
        }
    }

    private Map<String, ZipArchiveEntry> inspectEntries(ZipFile zip) throws IOException {
        Map<String, ZipArchiveEntry> entries = new HashMap<>();
        Enumeration<ZipArchiveEntry> enumeration = zip.getEntries();
        long totalSize = 0;
        while (enumeration.hasMoreElements()) {
            ZipArchiveEntry entry = enumeration.nextElement();
            if (entries.size() >= MAX_ENTRIES) {
                throw new TestcasePackageException("测试包文件数量超过限制");
            }
            String name = entry.getName();
            validateEntryName(name);
            if (entry.isDirectory() || entry.isUnixSymlink() || !zip.canReadEntryData(entry)) {
                throw new TestcasePackageException("测试包包含不允许的条目：" + name);
            }
            long size = entry.getSize();
            long compressedSize = entry.getCompressedSize();
            if (size < 0 || compressedSize < 0 || size > MAX_ENTRY_BYTES) {
                throw new TestcasePackageException("测试包条目大小非法：" + name);
            }
            if (compressedSize > 0 && size > compressedSize * 100L) {
                throw new TestcasePackageException("测试包条目压缩比超过限制：" + name);
            }
            totalSize = addSize(totalSize, size);
            if (entries.putIfAbsent(name, entry) != null) {
                throw new TestcasePackageException("测试包包含重名条目：" + name);
            }
        }
        return entries;
    }

    private long addSize(long current, long added) {
        try {
            long total = Math.addExact(current, added);
            if (total > MAX_ARCHIVE_BYTES) {
                throw new TestcasePackageException("测试包解压后总大小超过限制");
            }
            return total;
        } catch (ArithmeticException exception) {
            throw new TestcasePackageException("测试包解压后总大小超过限制", exception);
        }
    }

    private void validateEntryName(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0
                || name.indexOf('\\') >= 0) {
            throw new TestcasePackageException("测试包包含非法路径");
        }
        Path path = Path.of(name);
        if (path.isAbsolute()
                || !path.normalize().toString().replace('\\', '/').equals(name)
                || name.startsWith("../")
                || name.contains("/../")) {
            throw new TestcasePackageException("测试包包含越界路径：" + name);
        }
        if (!name.equals("manifest.json") && !name.startsWith("cases/")) {
            throw new TestcasePackageException("测试包条目不在允许目录内：" + name);
        }
    }

    private TestcaseManifest parseManifest(byte[] bytes) {
        try {
            return objectMapper.readValue(bytes, TestcaseManifest.class);
        } catch (IOException exception) {
            throw new TestcasePackageException("manifest.json 不符合严格结构", exception);
        }
    }

    private void validateManifest(TestcaseManifest manifest, long expectedProblemId) {
        if (manifest.schemaVersion() != 1) {
            throw new TestcasePackageException("测试包 schemaVersion 必须为 1");
        }
        if (manifest.problemId() != expectedProblemId) {
            throw new TestcasePackageException("测试包 problemId 与任务不一致");
        }
        if (manifest.cases() == null
                || manifest.cases().isEmpty()
                || manifest.cases().size() > MAX_CASES) {
            throw new TestcasePackageException(
                    "测试点数量必须在 1 到 " + MAX_CASES + " 之间");
        }
    }

    private void validateDefinition(
            TestcaseManifest.TestcaseDefinition definition,
            Set<String> caseIds,
            Set<String> referencedEntries
    ) {
        if (definition == null
                || definition.id() == null
                || !CASE_ID.matcher(definition.id()).matches()
                || !caseIds.add(definition.id())) {
            throw new TestcasePackageException("测试点 ID 非法或重复");
        }
        validateReferencedPath(definition.input());
        validateReferencedPath(definition.output());
        if (definition.input().equals(definition.output())
                || !referencedEntries.add(definition.input())
                || !referencedEntries.add(definition.output())) {
            throw new TestcasePackageException("测试点文件路径重复：" + definition.id());
        }
        if (definition.inputSha256() == null
                || definition.outputSha256() == null
                || !SHA256.matcher(definition.inputSha256()).matches()
                || !SHA256.matcher(definition.outputSha256()).matches()) {
            throw new TestcasePackageException("测试点散列格式非法：" + definition.id());
        }
        if (definition.score() == null
                || definition.score().compareTo(BigDecimal.ZERO) <= 0
                || definition.score().scale() > 2) {
            throw new TestcasePackageException("测试点分值非法：" + definition.id());
        }
    }

    private void validateReferencedPath(String name) {
        validateEntryName(name);
        if (!name.startsWith("cases/")) {
            throw new TestcasePackageException("测试点文件必须位于 cases/ 目录");
        }
    }

    private byte[] readRequired(
            ZipFile zip,
            Map<String, ZipArchiveEntry> entries,
            String name
    ) throws IOException {
        ZipArchiveEntry entry = entries.get(name);
        if (entry == null) {
            throw new TestcasePackageException("manifest 引用的文件不存在：" + name);
        }
        return read(zip, entry, MAX_ENTRY_BYTES);
    }

    private byte[] read(ZipFile zip, ZipArchiveEntry entry, int limit) throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit || input.read() != -1) {
                throw new TestcasePackageException(
                        "测试包条目实际大小超过限制：" + entry.getName());
            }
            return bytes;
        }
    }

    static long addActualSize(long currentSize, int inputSize, int outputSize) {
        long total;
        try {
            total = Math.addExact(
                    currentSize,
                    Math.addExact((long) inputSize, outputSize)
            );
        } catch (ArithmeticException exception) {
            throw new TestcasePackageException("测试包实际解压总大小超过限制", exception);
        }
        if (total > MAX_ARCHIVE_BYTES) {
            throw new TestcasePackageException("测试包实际解压总大小超过限制");
        }
        return total;
    }
}
