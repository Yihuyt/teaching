package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.storage.ObjectStorageIntegrityVerifier;
import cn.utcy.teaching.judgecontract.Sha256;
import cn.utcy.teaching.judgecontract.TestcasePackageReader;
import cn.utcy.teaching.judgecontract.TestcasePackageWriter;
import cn.utcy.teaching.programming.application.ProgrammingProblemApplicationService.ProblemManagementDetailView;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.domain.ProgrammingProblemProvenance;
import cn.utcy.teaching.programming.domain.ProgrammingProblemSample;
import cn.utcy.teaching.programming.infrastructure.ProblemPackageException;
import cn.utcy.teaching.programming.infrastructure.ProblemPackageReader;
import cn.utcy.teaching.programming.infrastructure.ProblemPackageReader.ParsedProblemPackage;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemProvenanceMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemSampleMapper;
import cn.utcy.teaching.programming.infrastructure.TestcaseObjectValidator;
import cn.utcy.teaching.programming.infrastructure.TestcaseOssProperties;
import cn.utcy.teaching.programming.infrastructure.TransactionalTestcaseObjectWriter;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Service
public class ProblemPackageImportApplicationService {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(ProblemPackageImportApplicationService.class);
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final ProblemPackageReader packageReader;
    private final TestcasePackageWriter testcaseWriter;
    private final TestcasePackageReader testcaseReader;
    private final ProgrammingProblemMapper problemMapper;
    private final ProgrammingProblemSampleMapper sampleMapper;
    private final ProgrammingProblemProvenanceMapper provenanceMapper;
    private final ProgrammingProblemApplicationService problems;
    private final CurrentActor currentActor;
    private final ObjectMapper objectMapper;
    private final TestcaseOssProperties testcaseOss;
    private final ObjectStorageIntegrityVerifier integrityVerifier;
    private final TestcaseObjectValidator testcaseValidator;
    private final TransactionalTestcaseObjectWriter objectWriter;
    private final TransactionTemplate transactions;
    private final CourseAccess courseAccess;

    public ProblemPackageImportApplicationService(
            ProblemPackageReader packageReader,
            TestcasePackageWriter testcaseWriter,
            TestcasePackageReader testcaseReader,
            ProgrammingProblemMapper problemMapper,
            ProgrammingProblemSampleMapper sampleMapper,
            ProgrammingProblemProvenanceMapper provenanceMapper,
            ProgrammingProblemApplicationService problems,
            CurrentActor currentActor,
            ObjectMapper objectMapper,
            TestcaseOssProperties testcaseOss,
            ObjectStorageIntegrityVerifier integrityVerifier,
            TestcaseObjectValidator testcaseValidator,
            TransactionalTestcaseObjectWriter objectWriter,
            TransactionTemplate transactions,
            CourseAccess courseAccess
    ) {
        this.packageReader = packageReader;
        this.testcaseWriter = testcaseWriter;
        this.testcaseReader = testcaseReader;
        this.problemMapper = problemMapper;
        this.sampleMapper = sampleMapper;
        this.provenanceMapper = provenanceMapper;
        this.problems = problems;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
        this.testcaseOss = testcaseOss;
        this.integrityVerifier = integrityVerifier;
        this.testcaseValidator = testcaseValidator;
        this.objectWriter = objectWriter;
        this.transactions = transactions;
        this.courseAccess = courseAccess;
    }

    public ProblemManagementDetailView importPackage(
            long courseId,
            MultipartFile file,
            ProblemDifficulty difficulty
    ) {
        Actor actor = currentActor.require();
        courseAccess.requireManagementAccess(courseId, actor);
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("请选择非空题目包");
        }
        if (difficulty == null) {
            throw new BadRequestException("题目难度不能为空");
        }

        try (TemporaryFile source = TemporaryFile.create(
                "teaching-problem-package-", ".zip")) {
            FileDigest sourceDigest = copyUpload(file, source.path());
            ParsedProblemPackage parsed;
            try {
                parsed = packageReader.read(
                        source.path(), file.getOriginalFilename());
            } catch (ProblemPackageException exception) {
                throw new BadRequestException(
                        "题目包不符合平台的 2025-09 配置：" + exception.getMessage());
            }
            ProblemManagementDetailView result = transactions.execute(status ->
                    persist(
                            status,
                            actor,
                            courseId,
                            difficulty,
                            source.path(),
                            sourceDigest,
                            parsed));
            if (result == null) {
                throw new IllegalStateException("题目包导入事务未返回结果");
            }
            return result;
        }
    }

    private ProblemManagementDetailView persist(
            TransactionStatus transaction,
            Actor actor,
            long courseId,
            ProblemDifficulty difficulty,
            Path sourceArchive,
            FileDigest sourceDigest,
            ParsedProblemPackage parsed
    ) {
        if (provenanceMapper.exists(
                new LambdaQueryWrapper<ProgrammingProblemProvenance>()
                        .eq(
                                ProgrammingProblemProvenance::getPackageUuid,
                                parsed.packageUuid()))) {
            throw new ConflictException("该 UUID 的标准题目包已经导入");
        }
        ProgrammingProblem problem = ProgrammingProblem.create(
                actor.userId(),
                courseId,
                parsed.title(),
                parsed.statementMarkdown(),
                difficulty,
                parsed.timeLimitMs(),
                parsed.memoryLimitMb(),
                parsed.outputLimitKb(),
                writeLanguages(parsed.languages()));
        requireMutation(problemMapper.insert(problem), "题目包对应编程题创建未生效");

        try (TemporaryFile testcase = TemporaryFile.create(
                "teaching-imported-testcases-", ".zip")) {
            testcaseWriter.write(
                    testcase.path(), problem.getId(), parsed.secretCases());
            testcaseReader.read(testcase.path(), problem.getId());
            FileDigest testcaseDigest = digest(testcase.path());
            String sourceObjectKey = ProblemPackageObjectKey.of(
                    problem.getId(), sourceDigest.sha256());
            String testcaseObjectKey = TestcaseObjectKey.of(
                    problem.getId(), testcaseDigest.sha256());
            objectWriter.uploadNew(
                    sourceArchive,
                    sourceObjectKey,
                    sourceDigest.sha256(),
                    sourceDigest.sizeBytes(),
                    "application/zip");
            integrityVerifier.verify(
                    testcaseOss.bucket(),
                    sourceObjectKey,
                    sourceDigest.sizeBytes(),
                    sourceDigest.sha256());
            objectWriter.uploadNew(
                    testcase.path(),
                    testcaseObjectKey,
                    testcaseDigest.sha256(),
                    testcaseDigest.sizeBytes(),
                    "application/zip");
            testcaseValidator.validate(
                    testcaseOss.bucket(),
                    testcaseObjectKey,
                    testcaseDigest.sizeBytes(),
                    testcaseDigest.sha256(),
                    problem.getId());

            insertSamples(problem.getId(), parsed);
            requireMutation(
                    provenanceMapper.insert(new ProgrammingProblemProvenance(
                            problem.getId(),
                            ProblemPackageReader.FORMAT_VERSION,
                            parsed.packageUuid(),
                            parsed.packageVersion(),
                            parsed.sourcesJson(),
                            parsed.creditsJson(),
                            parsed.licenseCode(),
                            parsed.rightsOwner(),
                            sourceDigest.sha256(),
                            sourceDigest.sizeBytes(),
                            sourceObjectKey,
                            Instant.now())),
                    "题目包来源信息保存未生效");
            problem.confirmTestcase(
                    testcaseDigest.sha256(), testcaseDigest.sizeBytes());
            requireMutation(
                    problemMapper.updateById(problem),
                    "题目包测试数据绑定未生效");
            return problems.managementDetailView(problem);
        } catch (RuntimeException exception) {
            transaction.setRollbackOnly();
            throw exception;
        }
    }

    private void insertSamples(
            long problemId,
            ParsedProblemPackage parsed
    ) {
        for (int index = 0; index < parsed.samples().size(); index++) {
            ProblemPackageReader.PublicSample sample = parsed.samples().get(index);
            requireMutation(
                    sampleMapper.insert(ProgrammingProblemSample.create(
                            problemId,
                            index + 1,
                            sample.input(),
                            sample.output())),
                    "题目包公开样例保存未生效");
        }
    }

    private FileDigest copyUpload(MultipartFile source, Path target) {
        if (source.getSize() < 1
                || source.getSize() > ProblemPackageReader.MAX_PACKAGE_BYTES) {
            throw new BadRequestException("题目包大小必须在 1 字节到 256 MiB 之间");
        }
        try (InputStream input = source.getInputStream();
             OutputStream output = Files.newOutputStream(target)) {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            long total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total = Math.addExact(total, read);
                if (total > ProblemPackageReader.MAX_PACKAGE_BYTES) {
                    throw new BadRequestException("题目包大小超过 256 MiB");
                }
                output.write(buffer, 0, read);
            }
            if (total != source.getSize()) {
                throw new BadRequestException("题目包实际大小与上传声明不一致");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取上传题目包失败", exception);
        } catch (ArithmeticException exception) {
            throw new BadRequestException("题目包大小超过 256 MiB");
        }
        return digest(target);
    }

    private FileDigest digest(Path file) {
        try {
            return new FileDigest(Sha256.file(file), Files.size(file));
        } catch (IOException exception) {
            throw new IllegalStateException("无法计算题目包文件散列", exception);
        }
    }

    private String writeLanguages(Set<ProgrammingLanguage> languages) {
        try {
            return objectMapper.writeValueAsString(EnumSet.copyOf(languages));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("标准题目包语言集合无法序列化", exception);
        }
    }

    private void requireMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new ConflictException(message);
        }
    }

    private record FileDigest(String sha256, long sizeBytes) {
    }

    private static final class TemporaryFile implements AutoCloseable {
        private final Path path;

        private TemporaryFile(Path path) {
            this.path = path;
        }

        static TemporaryFile create(String prefix, String suffix) {
            try {
                return new TemporaryFile(Files.createTempFile(prefix, suffix));
            } catch (IOException exception) {
                throw new IllegalStateException("无法创建题目包临时文件", exception);
            }
        }

        Path path() {
            return path;
        }

        @Override
        public void close() {
            try {
                Files.deleteIfExists(path);
            } catch (IOException exception) {
                LOGGER.warn("无法清理题目包临时文件：{}", path, exception);
            }
        }
    }
}
