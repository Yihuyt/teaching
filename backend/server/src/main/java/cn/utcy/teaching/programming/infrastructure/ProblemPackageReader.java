package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.judgecontract.TestcasePackageReader;
import cn.utcy.teaching.judgecontract.TestcasePackageWriter.TestcaseSource;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict parser for the platform's documented ICPC/Kattis Problem Package
 * Format 2025-09 profile. Unsupported standard features are rejected rather
 * than silently discarded.
 */
@Component
public final class ProblemPackageReader {

    public static final String FORMAT_VERSION = "2025-09";
    public static final long MAX_PACKAGE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_YAML_BYTES = 1024 * 1024;
    private static final int MAX_STATEMENT_BYTES = 8 * 1024 * 1024;
    private static final int MAX_SAMPLE_BYTES = 1024 * 1024;
    private static final int MAX_ENTRIES = 2_000;
    private static final int MAX_METADATA_JSON_CHARS = 32_000;
    private static final Pattern SHORT_NAME = Pattern.compile("[a-z0-9]+");
    private static final Pattern COMPONENT =
            Pattern.compile("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,254}");
    private static final Pattern CASE_FILE =
            Pattern.compile("[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,250}");
    private static final Pattern LANGUAGE_CODE =
            Pattern.compile("[a-z]{2,3}(?:-[A-Z]{2})?");
    private static final Pattern ORCID =
            Pattern.compile("[0-9]{4}-[0-9]{4}-[0-9]{4}-[0-9X]{4}");
    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "problem_format_version",
            "type",
            "name",
            "uuid",
            "version",
            "credits",
            "source",
            "license",
            "rights_owner",
            "limits",
            "languages",
            "allow_file_writing");
    private static final Set<String> LIMIT_KEYS =
            Set.of("time_limit", "memory", "output");
    private static final Set<String> LICENSES = Set.of(
            "unknown",
            "public domain",
            "cc0",
            "cc by",
            "cc by-sa",
            "educational",
            "permission");
    private static final Set<String> CREDIT_KEYS = Set.of(
            "authors",
            "contributors",
            "testers",
            "translators",
            "packagers",
            "acknowledgements");
    private static final Set<String> PERSON_KEYS =
            Set.of("name", "email", "orcid", "kattis");

    private final ObjectMapper objectMapper;
    private final Yaml yaml;

    public ProblemPackageReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setAllowRecursiveKeys(false);
        options.setNestingDepthLimit(20);
        options.setCodePointLimit(MAX_YAML_BYTES);
        this.yaml = new Yaml(new SafeConstructor(options));
    }

    public ParsedProblemPackage read(Path archive, String archiveFilename) {
        requireArchiveSize(archive);
        String shortName = shortName(archiveFilename);
        try (ZipFile zip = ZipFile.builder().setPath(archive).get()) {
            Map<String, ZipArchiveEntry> entries = inspect(zip, shortName);
            String prefix = shortName + "/";
            Map<String, Object> metadata = parseYaml(
                    readRequired(zip, entries, prefix + "problem.yaml", MAX_YAML_BYTES));
            validateKeys(metadata, TOP_LEVEL_KEYS, "problem.yaml");

            String formatVersion = requireString(
                    metadata, "problem_format_version", 32, false);
            if (!FORMAT_VERSION.equals(formatVersion)) {
                throw error("problem_format_version 必须严格为 " + FORMAT_VERSION);
            }
            requirePassFail(metadata.get("type"));
            String title = requireChineseName(metadata.get("name"));
            String packageUuid = requireUuid(metadata.get("uuid"));
            String packageVersion = optionalString(
                    metadata.get("version"), "version", 64);
            String creditsJson = creditsJson(metadata.get("credits"));
            String sourcesJson = sourcesJson(metadata.get("source"));
            String license = optionalString(
                    metadata.get("license"), "license", 32);
            if (license == null) {
                license = "unknown";
            }
            if (!LICENSES.contains(license)) {
                throw error("license 不是 2025-09 规范允许值");
            }
            String rightsOwner = optionalString(
                    metadata.get("rights_owner"), "rights_owner", 255);
            validateRightsOwner(license, rightsOwner);
            requireFileWritingDisabled(metadata.get("allow_file_writing"));

            Limits limits = limits(metadata.get("limits"));
            Set<ProgrammingLanguage> languages = languages(metadata.get("languages"));
            String statement = decodeText(
                    readRequired(
                            zip,
                            entries,
                            prefix + "statement/problem.zh.md",
                            MAX_STATEMENT_BYTES),
                    "statement/problem.zh.md");
            List<PublicSample> samples = samples(zip, entries, prefix);
            List<TestcaseSource> secrets = secrets(zip, entries, prefix);

            return new ParsedProblemPackage(
                    shortName,
                    title,
                    statement,
                    packageUuid,
                    packageVersion,
                    sourcesJson,
                    creditsJson,
                    license,
                    rightsOwner,
                    limits.timeLimitMs(),
                    limits.memoryLimitMb(),
                    limits.outputLimitKb(),
                    languages,
                    samples,
                    secrets);
        } catch (ProblemPackageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ProblemPackageException("无法读取题目包 ZIP", exception);
        }
    }

    private void requireArchiveSize(Path archive) {
        try {
            long size = Files.size(archive);
            if (size < 1 || size > MAX_PACKAGE_BYTES) {
                throw error("题目包大小必须在 1 字节到 256 MiB 之间");
            }
        } catch (IOException exception) {
            throw new ProblemPackageException("无法读取题目包大小", exception);
        }
    }

    private String shortName(String filename) {
        if (filename == null
                || filename.isBlank()
                || filename.contains("/")
                || filename.contains("\\")) {
            throw error("题目包文件名必须是短名称加 .zip 或 .kpp");
        }
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!filename.equals(lower)) {
            throw error("题目包文件名和扩展名必须使用小写");
        }
        String extension;
        if (lower.endsWith(".zip")) {
            extension = ".zip";
        } else if (lower.endsWith(".kpp")) {
            extension = ".kpp";
        } else {
            throw error("题目包扩展名必须是 .zip 或 .kpp");
        }
        String result = filename.substring(0, filename.length() - extension.length());
        if (result.length() > 255 || !SHORT_NAME.matcher(result).matches()) {
            throw error("题目包短名称只能包含小写字母和数字");
        }
        return result;
    }

    private Map<String, ZipArchiveEntry> inspect(
            ZipFile zip,
            String shortName
    ) throws IOException {
        Map<String, ZipArchiveEntry> result = new HashMap<>();
        Enumeration<ZipArchiveEntry> enumeration = zip.getEntries();
        long totalSize = 0;
        String prefix = shortName + "/";
        while (enumeration.hasMoreElements()) {
            ZipArchiveEntry entry = enumeration.nextElement();
            if (result.size() >= MAX_ENTRIES) {
                throw error("题目包文件数量超过 2000");
            }
            String name = entry.getName();
            validateEntryName(name, prefix);
            if (entry.isUnixSymlink() || !zip.canReadEntryData(entry)) {
                throw error("题目包包含不允许的链接或不可读条目：" + name);
            }
            long size = entry.getSize();
            long compressedSize = entry.getCompressedSize();
            if (size < 0
                    || compressedSize < 0
                    || size > TestcasePackageReader.MAX_ENTRY_BYTES) {
                throw error("题目包条目大小非法：" + name);
            }
            if (compressedSize > 0 && size > compressedSize * 100L) {
                throw error("题目包条目压缩比超过 100：" + name);
            }
            try {
                totalSize = Math.addExact(totalSize, size);
            } catch (ArithmeticException exception) {
                throw new ProblemPackageException("题目包解压后总大小超过限制", exception);
            }
            if (totalSize > MAX_PACKAGE_BYTES) {
                throw error("题目包解压后总大小超过 256 MiB");
            }
            if (result.putIfAbsent(name, entry) != null) {
                throw error("题目包包含重名条目：" + name);
            }
            classifyEntry(entry, name, prefix);
        }
        if (result.isEmpty()) {
            throw error("题目包为空");
        }
        return result;
    }

    private void validateEntryName(String name, String prefix) {
        if (name == null
                || name.isBlank()
                || name.indexOf('\0') >= 0
                || name.indexOf('\\') >= 0
                || name.startsWith("/")
                || !name.startsWith(prefix)
                || name.contains("//")) {
            throw error("题目包包含非法或不匹配短名称的路径");
        }
        String normalized = name.endsWith("/")
                ? name.substring(0, name.length() - 1)
                : name;
        for (String component : normalized.split("/")) {
            if (!COMPONENT.matcher(component).matches()) {
                throw error("题目包路径片段不符合 2025-09 规范：" + name);
            }
        }
    }

    private void classifyEntry(
            ZipArchiveEntry entry,
            String name,
            String prefix
    ) {
        String relative = name.substring(prefix.length());
        if (entry.isDirectory()) {
            String directory = relative.endsWith("/")
                    ? relative.substring(0, relative.length() - 1)
                    : relative;
            if (directory.isEmpty()
                    || directory.equals("statement")
                    || directory.equals("data")
                    || directory.equals("data/sample")
                    || directory.equals("data/secret")
                    || directory.equals("submissions")
                    || directory.startsWith("submissions/")
                    || directory.equals("input_validators")
                    || directory.startsWith("input_validators/")) {
                return;
            }
            throw error("平台当前题目包配置不支持目录：" + relative);
        }
        if (relative.equals("problem.yaml")
                || relative.equals("statement/problem.zh.md")
                || isCaseFile(relative, "data/sample/", true)
                || isCaseFile(relative, "data/secret/", false)
                || relative.startsWith("submissions/")
                || relative.startsWith("input_validators/")) {
            return;
        }
        throw error("平台当前题目包配置不支持条目：" + relative);
    }

    private boolean isCaseFile(
            String relative,
            String directory,
            boolean allowOut
    ) {
        if (!relative.startsWith(directory)) {
            return false;
        }
        String filename = relative.substring(directory.length());
        if (filename.contains("/") || filename.isEmpty()) {
            return false;
        }
        String suffix;
        if (filename.endsWith(".in")) {
            suffix = ".in";
        } else if (filename.endsWith(".ans")) {
            suffix = ".ans";
        } else if (allowOut && filename.endsWith(".out")) {
            suffix = ".out";
        } else {
            return false;
        }
        String base = filename.substring(0, filename.length() - suffix.length());
        return CASE_FILE.matcher(base).matches();
    }

    private byte[] readRequired(
            ZipFile zip,
            Map<String, ZipArchiveEntry> entries,
            String name,
            int limit
    ) throws IOException {
        ZipArchiveEntry entry = entries.get(name);
        if (entry == null || entry.isDirectory()) {
            throw error("题目包缺少必需文件：" + name.substring(name.indexOf('/') + 1));
        }
        return read(zip, entry, limit);
    }

    private byte[] read(ZipFile zip, ZipArchiveEntry entry, int limit)
            throws IOException {
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] content = input.readNBytes(limit + 1);
            if (content.length > limit || input.read() != -1) {
                throw error("题目包条目实际大小超过限制：" + entry.getName());
            }
            return content;
        }
    }

    private Map<String, Object> parseYaml(byte[] bytes) {
        String content = decodeText(bytes, "problem.yaml");
        Object value;
        try {
            value = yaml.load(content);
        } catch (RuntimeException exception) {
            throw new ProblemPackageException(
                    "problem.yaml 不是安全且无重复键的 YAML", exception);
        }
        return stringMap(value, "problem.yaml");
    }

    private void validateKeys(
            Map<String, Object> values,
            Set<String> allowed,
            String location
    ) {
        Set<String> unknown = new LinkedHashSet<>(values.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw error(location + " 包含平台未实现的字段：" + String.join(", ", unknown));
        }
    }

    private void requirePassFail(Object value) {
        if (value == null || "pass-fail".equals(value)) {
            return;
        }
        if (value instanceof List<?> list
                && list.size() == 1
                && "pass-fail".equals(list.getFirst())) {
            return;
        }
        throw error("平台当前只支持 type: pass-fail");
    }

    private String requireChineseName(Object value) {
        Map<String, Object> names = stringMap(value, "name");
        if (!names.keySet().equals(Set.of("zh"))) {
            throw error("name 必须且只能包含 zh 中文名称");
        }
        return requireText(names.get("zh"), "name.zh", 255, false);
    }

    private String requireUuid(Object value) {
        String raw = requireText(value, "uuid", 36, false);
        try {
            String normalized = UUID.fromString(raw).toString();
            if (!normalized.equals(raw.toLowerCase(Locale.ROOT))) {
                throw error("uuid 必须是规范的 UUID 文本");
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw new ProblemPackageException("uuid 必须是规范的 UUID 文本", exception);
        }
    }

    private String creditsJson(Object value) {
        if (value == null) {
            return null;
        }
        Object normalized;
        if (value instanceof String) {
            normalized = Map.of(
                    "authors",
                    List.of(normalizePerson(value, "credits.authors")));
        } else {
            Map<String, Object> credits = stringMap(value, "credits");
            validateKeys(credits, CREDIT_KEYS, "credits");
            Map<String, Object> mapped = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : credits.entrySet()) {
                if (entry.getKey().equals("translators")) {
                    mapped.put(entry.getKey(), normalizeTranslators(entry.getValue()));
                } else {
                    mapped.put(
                            entry.getKey(),
                            normalizePeople(
                                    entry.getValue(),
                                    "credits." + entry.getKey()));
                }
            }
            normalized = mapped;
        }
        return writeMetadataJson(normalized, "credits");
    }

    private Map<String, Object> normalizeTranslators(Object value) {
        Map<String, Object> translators = stringMap(value, "credits.translators");
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : translators.entrySet()) {
            if (!LANGUAGE_CODE.matcher(entry.getKey()).matches()) {
                throw error("credits.translators 包含非法语言代码");
            }
            normalized.put(
                    entry.getKey(),
                    normalizePeople(
                            entry.getValue(),
                            "credits.translators." + entry.getKey()));
        }
        return normalized;
    }

    private List<Object> normalizePeople(Object value, String location) {
        if (value == null) {
            throw error(location + " 不能为空");
        }
        List<?> raw = value instanceof List<?> list ? list : List.of(value);
        if (raw.isEmpty() || raw.size() > 50) {
            throw error(location + " 必须包含 1 到 50 个人员");
        }
        List<Object> result = new ArrayList<>(raw.size());
        for (Object person : raw) {
            result.add(normalizePerson(person, location));
        }
        return List.copyOf(result);
    }

    private Object normalizePerson(Object value, String location) {
        if (value instanceof String) {
            String person = requireText(value, location, 576, false);
            int emailStart = person.lastIndexOf('<');
            if (emailStart < 0) {
                if (person.indexOf('>') >= 0) {
                    throw error(location + " 人员字符串中的邮箱格式不正确");
                }
                return Map.of("name", person);
            }
            if (!person.endsWith(">")
                    || person.indexOf('<') != emailStart
                    || person.substring(0, emailStart).trim().isEmpty()) {
                throw error(location + " 人员字符串中的邮箱格式不正确");
            }
            String email = person.substring(emailStart + 1, person.length() - 1);
            if (email.isBlank()
                    || email.indexOf('<') >= 0
                    || email.indexOf('>') >= 0) {
                throw error(location + " 人员字符串中的邮箱格式不正确");
            }
            String name = person.substring(0, emailStart).trim();
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put(
                    "name",
                    requireText(name, location + ".name", 255, false));
            normalized.put(
                    "email",
                    requireText(email, location + ".email", 320, false));
            return normalized;
        }
        Map<String, Object> person = stringMap(value, location);
        validateKeys(person, PERSON_KEYS, location);
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put(
                "name",
                requireText(person.get("name"), location + ".name", 255, false));
        optionalMappedText(person, normalized, "email", location, 320);
        optionalMappedText(person, normalized, "orcid", location, 19);
        optionalMappedText(person, normalized, "kattis", location, 64);
        if (normalized.containsKey("orcid")
                && !ORCID.matcher((String) normalized.get("orcid")).matches()) {
            throw error(location + ".orcid 格式不正确");
        }
        return normalized;
    }

    private void optionalMappedText(
            Map<String, Object> source,
            Map<String, Object> target,
            String key,
            String location,
            int maxLength
    ) {
        String value = optionalString(
                source.get(key), location + "." + key, maxLength);
        if (value != null) {
            target.put(key, value);
        }
    }

    private String sourcesJson(Object value) {
        if (value == null) {
            return null;
        }
        List<?> values = value instanceof List<?> list ? list : List.of(value);
        if (values.isEmpty() || values.size() > 20) {
            throw error("source 必须包含 1 到 20 个来源");
        }
        List<Map<String, String>> sources = new ArrayList<>(values.size());
        for (Object source : values) {
            if (source instanceof String) {
                sources.add(sourceEntry(requireText(source, "source.name", 255, false), null));
                continue;
            }
            Map<String, Object> map = stringMap(source, "source");
            validateKeys(map, Set.of("name", "url"), "source");
            String name = requireText(map.get("name"), "source.name", 255, false);
            String url = optionalString(map.get("url"), "source.url", 2048);
            if (url != null) {
                validateWebUrl(url);
            }
            sources.add(sourceEntry(name, url));
        }
        return writeMetadataJson(sources, "source");
    }

    /** 落库 JSON 字段齐全(url 无则为 null),读回时走严格反序列化不缺字段 */
    private static Map<String, String> sourceEntry(String name, String url) {
        Map<String, String> entry = new java.util.LinkedHashMap<>();
        entry.put("name", name);
        entry.put("url", url);
        return entry;
    }

    private void validateWebUrl(String value) {
        try {
            URI uri = new URI(value);
            if (!Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null) {
                throw error("source.url 必须是无账号信息的 HTTP 或 HTTPS 地址");
            }
        } catch (URISyntaxException exception) {
            throw new ProblemPackageException("source.url 格式不正确", exception);
        }
    }

    private void validateRightsOwner(String license, String rightsOwner) {
        if ("public domain".equals(license) && rightsOwner != null) {
            throw error("public domain 题目包不得填写 rights_owner");
        }
        if (!Set.of("unknown", "public domain").contains(license)
                && rightsOwner == null) {
            throw error("当前平台要求非 unknown/public domain 许可显式填写 rights_owner");
        }
    }

    private void requireFileWritingDisabled(Object value) {
        if (value != null && !Boolean.FALSE.equals(value)) {
            throw error("平台不支持 allow_file_writing: true");
        }
    }

    private Limits limits(Object value) {
        Map<String, Object> limits = stringMap(value, "limits");
        validateKeys(limits, LIMIT_KEYS, "limits");
        if (!limits.keySet().equals(LIMIT_KEYS)) {
            throw error("limits 必须显式包含 time_limit、memory 和 output");
        }
        BigDecimal seconds = decimal(limits.get("time_limit"), "limits.time_limit");
        int timeLimitMs;
        try {
            timeLimitMs = seconds.movePointRight(3).intValueExact();
        } catch (ArithmeticException exception) {
            throw new ProblemPackageException(
                    "limits.time_limit 必须精确到毫秒", exception);
        }
        int memory = integer(limits.get("memory"), "limits.memory");
        int outputMiB = integer(limits.get("output"), "limits.output");
        if (timeLimitMs < 100 || timeLimitMs > 30_000) {
            throw error("limits.time_limit 必须在 0.1 到 30 秒之间");
        }
        if (memory < 16 || memory > 2_048) {
            throw error("limits.memory 必须在 16 到 2048 MiB 之间");
        }
        if (outputMiB < 1 || outputMiB > 64) {
            throw error("limits.output 必须在 1 到 64 MiB 之间");
        }
        return new Limits(timeLimitMs, memory, outputMiB * 1_024);
    }

    private Set<ProgrammingLanguage> languages(Object value) {
        if (!(value instanceof List<?> values) || values.isEmpty()) {
            throw error("languages 必须是非空列表，不能使用 all");
        }
        Set<ProgrammingLanguage> result = new LinkedHashSet<>();
        for (Object raw : values) {
            String code = requireText(raw, "languages", 32, false);
            ProgrammingLanguage language = switch (code) {
                case "c" -> ProgrammingLanguage.C17;
                case "cpp" -> ProgrammingLanguage.CPP20;
                case "python3" -> ProgrammingLanguage.PYTHON312;
                default -> throw error("平台不支持题目包语言代码：" + code);
            };
            if (!result.add(language)) {
                throw error("languages 不能包含重复项");
            }
        }
        return Set.copyOf(result);
    }

    private List<PublicSample> samples(
            ZipFile zip,
            Map<String, ZipArchiveEntry> entries,
            String prefix
    ) throws IOException {
        Map<String, ZipArchiveEntry> inputs =
                caseEntries(entries, prefix + "data/sample/", ".in");
        if (inputs.size() > 50) {
            throw error("公开样例不能超过 50 组");
        }
        List<String> bases = inputs.keySet().stream().sorted().toList();
        List<PublicSample> result = new ArrayList<>(bases.size());
        for (String base : bases) {
            ZipArchiveEntry answer = entries.get(prefix + "data/sample/" + base + ".ans");
            ZipArchiveEntry output = entries.get(prefix + "data/sample/" + base + ".out");
            if ((answer == null) == (output == null)) {
                throw error("公开样例必须且只能有一个 .ans 或 .out：" + base);
            }
            byte[] inputBytes = read(zip, inputs.get(base), MAX_SAMPLE_BYTES);
            byte[] outputBytes = read(
                    zip, answer != null ? answer : output, MAX_SAMPLE_BYTES);
            result.add(new PublicSample(
                    decodeText(inputBytes, "data/sample/" + base + ".in"),
                    decodeText(
                            outputBytes,
                            "data/sample/" + base + (answer != null ? ".ans" : ".out"))));
        }
        rejectOrphanCaseFiles(entries, prefix + "data/sample/", inputs.keySet(), true);
        return List.copyOf(result);
    }

    private List<TestcaseSource> secrets(
            ZipFile zip,
            Map<String, ZipArchiveEntry> entries,
            String prefix
    ) throws IOException {
        Map<String, ZipArchiveEntry> inputs =
                caseEntries(entries, prefix + "data/secret/", ".in");
        if (inputs.isEmpty()
                || inputs.size() > TestcasePackageReader.MAX_CASES) {
            throw error("隐藏测试点数量必须在 1 到 500 之间");
        }
        List<String> bases = inputs.keySet().stream().sorted().toList();
        List<TestcaseSource> result = new ArrayList<>(bases.size());
        for (int index = 0; index < bases.size(); index++) {
            String base = bases.get(index);
            ZipArchiveEntry answer =
                    entries.get(prefix + "data/secret/" + base + ".ans");
            if (answer == null) {
                throw error("隐藏测试点缺少 .ans：" + base);
            }
            byte[] input = read(
                    zip, inputs.get(base), TestcasePackageReader.MAX_ENTRY_BYTES);
            byte[] output = read(
                    zip, answer, TestcasePackageReader.MAX_ENTRY_BYTES);
            decodeText(input, "data/secret/" + base + ".in");
            decodeText(output, "data/secret/" + base + ".ans");
            result.add(new TestcaseSource(
                    "secret-" + String.format(Locale.ROOT, "%04d", index + 1),
                    input,
                    output));
        }
        rejectOrphanCaseFiles(entries, prefix + "data/secret/", inputs.keySet(), false);
        return List.copyOf(result);
    }

    private Map<String, ZipArchiveEntry> caseEntries(
            Map<String, ZipArchiveEntry> entries,
            String directory,
            String suffix
    ) {
        Map<String, ZipArchiveEntry> result = new HashMap<>();
        for (Map.Entry<String, ZipArchiveEntry> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (!entry.getValue().isDirectory()
                    && name.startsWith(directory)
                    && name.endsWith(suffix)) {
                String base = name.substring(
                        directory.length(), name.length() - suffix.length());
                result.put(base, entry.getValue());
            }
        }
        return result;
    }

    private void rejectOrphanCaseFiles(
            Map<String, ZipArchiveEntry> entries,
            String directory,
            Set<String> inputBases,
            boolean allowOut
    ) {
        for (Map.Entry<String, ZipArchiveEntry> entry : entries.entrySet()) {
            String name = entry.getKey();
            if (entry.getValue().isDirectory() || !name.startsWith(directory)) {
                continue;
            }
            String filename = name.substring(directory.length());
            String suffix = filename.endsWith(".in")
                    ? ".in"
                    : filename.endsWith(".ans")
                    ? ".ans"
                    : allowOut && filename.endsWith(".out") ? ".out" : null;
            if (suffix == null) {
                continue;
            }
            String base = filename.substring(0, filename.length() - suffix.length());
            if (!inputBases.contains(base)) {
                throw error("测试数据存在没有对应 .in 的输出文件：" + filename);
            }
        }
    }

    private String decodeText(byte[] bytes, String location) {
        if (bytes.length >= 3
                && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF) {
            throw error(location + " 不能包含 UTF-8 BOM");
        }
        for (byte value : bytes) {
            if (value == '\r') {
                throw error(location + " 必须使用 LF 换行");
            }
        }
        if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
            throw error(location + " 非空时必须以 LF 结尾");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new ProblemPackageException(
                    location + " 不是有效 UTF-8 文本", exception);
        }
    }

    private Map<String, Object> stringMap(Object value, String location) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw error(location + " 必须是映射");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)
                    || result.putIfAbsent(key, entry.getValue()) != null) {
                throw error(location + " 包含非字符串键或重复键");
            }
        }
        return result;
    }

    private String requireString(
            Map<String, Object> values,
            String key,
            int maxLength,
            boolean allowBlank
    ) {
        return requireText(values.get(key), key, maxLength, allowBlank);
    }

    private String optionalString(
            Object value,
            String location,
            int maxLength
    ) {
        if (value == null) {
            return null;
        }
        return requireText(value, location, maxLength, false);
    }

    private String requireText(
            Object value,
            String location,
            int maxLength,
            boolean allowBlank
    ) {
        if (!(value instanceof String text)
                || text.length() > maxLength
                || (!allowBlank && text.isBlank())
                || !text.equals(text.trim())) {
            throw error(location + " 必须是长度不超过 " + maxLength + " 的非空整洁文本");
        }
        return text;
    }

    private int integer(Object value, String location) {
        try {
            return decimal(value, location).intValueExact();
        } catch (ArithmeticException exception) {
            throw new ProblemPackageException(location + " 必须是整数", exception);
        }
    }

    private BigDecimal decimal(Object value, String location) {
        if (!(value instanceof Number)) {
            throw error(location + " 必须是数字");
        }
        try {
            BigDecimal result = new BigDecimal(value.toString());
            if (result.signum() <= 0) {
                throw error(location + " 必须大于零");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new ProblemPackageException(location + " 数字格式不正确", exception);
        }
    }

    private String writeMetadataJson(Object value, String location) {
        try {
            String json = objectMapper.writeValueAsString(value);
            if (json.length() > MAX_METADATA_JSON_CHARS) {
                throw error(location + " 结构化元数据超过平台限制");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new ProblemPackageException(
                    location + " 无法转换为结构化元数据", exception);
        }
    }

    private ProblemPackageException error(String message) {
        return new ProblemPackageException(message);
    }

    private record Limits(
            int timeLimitMs,
            int memoryLimitMb,
            int outputLimitKb
    ) {
    }

    public record ParsedProblemPackage(
            String shortName,
            String title,
            String statementMarkdown,
            String packageUuid,
            String packageVersion,
            String sourcesJson,
            String creditsJson,
            String licenseCode,
            String rightsOwner,
            int timeLimitMs,
            int memoryLimitMb,
            int outputLimitKb,
            Set<ProgrammingLanguage> languages,
            List<PublicSample> samples,
            List<TestcaseSource> secretCases
    ) {
    }

    public record PublicSample(String input, String output) {
    }
}
