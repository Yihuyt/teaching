package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.shared.storage.ObjectStorageDeletionQueue;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.judgecontract.Sha256;
import cn.utcy.teaching.judgecontract.TestcasePackageReader;
import cn.utcy.teaching.judgecontract.TestcasePackageWriter;
import cn.utcy.teaching.judgecontract.TestcasePackageWriter.TestcaseSource;
import cn.utcy.teaching.programming.application.ProgrammingProblemApplicationService.ProblemManagementDetailView;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.programming.infrastructure.TestcaseOssProperties;
import cn.utcy.teaching.programming.infrastructure.TestcaseObjectValidator;
import cn.utcy.teaching.programming.infrastructure.TransactionalTestcaseObjectWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class TestcaseApplicationService {
    private final ProgrammingProblemApplicationService problems;
    private final ProgrammingProblemMapper problemMapper;
    private final TestcaseOssProperties testcaseOss;
    private final TestcaseObjectValidator objectValidator;
    private final ObjectStorageDeletionQueue deletionQueue;
    private final TestcasePackageWriter packageWriter;
    private final TestcasePackageReader packageReader;
    private final TransactionalTestcaseObjectWriter transactionalWriter;
    private final CurrentActor currentActor;
    private final CourseAccess courseAccess;

    public TestcaseApplicationService(
            ProgrammingProblemApplicationService problems,
            ProgrammingProblemMapper problemMapper,
            TestcaseOssProperties testcaseOss,
            TestcaseObjectValidator objectValidator,
            ObjectStorageDeletionQueue deletionQueue,
            TestcasePackageWriter packageWriter,
            TestcasePackageReader packageReader,
            TransactionalTestcaseObjectWriter transactionalWriter,
            CurrentActor currentActor,
            CourseAccess courseAccess
    ) {
        this.problems = problems;
        this.problemMapper = problemMapper;
        this.testcaseOss = testcaseOss;
        this.objectValidator = objectValidator;
        this.deletionQueue = deletionQueue;
        this.packageWriter = packageWriter;
        this.packageReader = packageReader;
        this.transactionalWriter = transactionalWriter;
        this.currentActor = currentActor;
        this.courseAccess = courseAccess;
    }

    @Transactional(readOnly = true)
    public List<TestcaseCaseView> listCases(long courseId, long problemId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        return cases(problems.requireProblem(courseId, problemId));
    }

    @Transactional
    public ProblemManagementDetailView createManualPackage(
            long courseId, long problemId, List<ManualTestcaseCommand> commands
    ) {
        return replaceWithManualPackage(problems.requireProblemForUpdate(courseId, problemId), commands);
    }

    @Transactional
    public void delete(long courseId, long problemId) {
        clear(problems.requireProblemForUpdate(courseId, problemId));
    }

    private List<TestcaseCaseView> cases(ProgrammingProblem problem) {
        long problemId = problem.getId();
        if (!problem.hasConfirmedTestcase()) {
            return List.of();
        }
        return objectValidator.read(
                        testcaseOss.bucket(),
                        TestcaseObjectKey.of(problemId, problem.getTestcaseSha256()),
                        problem.getTestcaseSizeBytes(),
                        problem.getTestcaseSha256(),
                        problemId)
                .cases()
                .stream()
                .map(testcase -> new TestcaseCaseView(
                        testcase.id(),
                        new String(testcase.input(), StandardCharsets.UTF_8),
                        new String(testcase.expectedOutput(), StandardCharsets.UTF_8)))
                .toList();
    }

    private ProblemManagementDetailView replaceWithManualPackage(
            ProgrammingProblem problem, List<ManualTestcaseCommand> commands
    ) {
        long problemId = problem.getId();
        requireMutable(problemId);
        requireManualTestcase(problemId);
        releaseCurrentTestcase(problem);
        List<TestcaseSource> sources = manualSources(commands);
        Path archive = createTemporaryArchive();
        RuntimeException failure = null;
        try {
            packageWriter.write(archive, problemId, sources);
            packageReader.read(archive, problemId);
            String sha256 = Sha256.file(archive);
            long sizeBytes = Files.size(archive);
            String objectKey = TestcaseObjectKey.of(problemId, sha256);
            transactionalWriter.uploadNew(
                    archive,
                    objectKey,
                    sha256,
                    sizeBytes,
                    "application/zip");
            objectValidator.validate(
                    testcaseOss.bucket(),
                    objectKey,
                    sizeBytes,
                    sha256,
                    problemId);
            problem.confirmTestcase(sha256, sizeBytes);
            problems.requireMutation(
                    problemMapper.updateById(problem),
                    "手工测试点绑定状态已变化，保存未生效");
            return problems.managementDetailView(problem);
        } catch (IOException exception) {
            failure = new IllegalStateException("无法读取生成后的测试包", exception);
            throw failure;
        } catch (RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            deleteTemporaryArchive(archive, failure);
        }
    }

    private void clear(ProgrammingProblem problem) {
        long problemId = problem.getId();
        requireMutable(problemId);
        requireManualTestcase(problemId);
        if (!problem.hasConfirmedTestcase()) {
            throw new NotFoundException("编程题尚未配置测试数据");
        }
        deletionQueue.enqueue(
                testcaseOss.bucket(),
                TestcaseObjectKey.of(problemId, problem.getTestcaseSha256()));
        problem.clearTestcase();
        problems.requireMutation(
                problemMapper.updateById(problem),
                "编程题测试包绑定状态已变化，删除未生效");
    }

    private void releaseCurrentTestcase(ProgrammingProblem problem) {
        if (!problem.hasConfirmedTestcase()) {
            return;
        }
        deletionQueue.enqueue(
                testcaseOss.bucket(),
                TestcaseObjectKey.of(problem.getId(), problem.getTestcaseSha256()));
        problem.clearTestcase();
    }

    private void requireMutable(long problemId) {
        if (problems.hasSubmissions(problemId)) {
            throw new ConflictException("编程题产生提交后不能更换或删除测试数据");
        }
    }

    private void requireManualTestcase(long problemId) {
        if (problems.isImported(problemId)) {
            throw new ConflictException(
                    "标准题目包导入的测试数据不能单独替换或删除；无引用且无提交时，请删除原题后重新导入修订包");
        }
    }

    private List<TestcaseSource> manualSources(
            List<ManualTestcaseCommand> commands
    ) {
        if (commands == null
                || commands.isEmpty()
                || commands.size() > TestcasePackageReader.MAX_CASES) {
            throw new BadRequestException("手工测试点数量必须在 1 到 500 之间");
        }
        List<TestcaseSource> result = new ArrayList<>(commands.size());
        long totalBytes = 0;
        for (int index = 0; index < commands.size(); index++) {
            ManualTestcaseCommand command = commands.get(index);
            if (command == null
                    || command.input() == null
                    || command.output() == null) {
                throw new BadRequestException("测试点输入和期望输出不能为空");
            }
            byte[] input = command.input().getBytes(StandardCharsets.UTF_8);
            byte[] output = command.output().getBytes(StandardCharsets.UTF_8);
            if (input.length > TestcasePackageReader.MAX_ENTRY_BYTES
                    || output.length > TestcasePackageReader.MAX_ENTRY_BYTES) {
                throw new BadRequestException("单个测试点输入或输出超过 64 MiB");
            }
            try {
                totalBytes = Math.addExact(
                        totalBytes,
                        Math.addExact((long) input.length, output.length));
            } catch (ArithmeticException exception) {
                throw new BadRequestException("手工测试点总大小超过 256 MiB");
            }
            if (totalBytes > TestcasePackageReader.MAX_ARCHIVE_BYTES) {
                throw new BadRequestException("手工测试点总大小超过 256 MiB");
            }
            result.add(new TestcaseSource(
                    "manual-" + String.format(
                            java.util.Locale.ROOT, "%04d", index + 1),
                    input,
                    output));
        }
        return List.copyOf(result);
    }

    private Path createTemporaryArchive() {
        try {
            return Files.createTempFile("teaching-manual-testcase-", ".zip");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建手工测试包临时文件", exception);
        }
    }

    private void deleteTemporaryArchive(
            Path archive,
            RuntimeException primaryFailure
    ) {
        try {
            Files.deleteIfExists(archive);
        } catch (IOException exception) {
            IllegalStateException cleanupFailure = new IllegalStateException(
                    "无法清理手工测试包临时文件", exception);
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
                return;
            }
            throw cleanupFailure;
        }
    }

    public record ManualTestcaseCommand(String input, String output) {
    }

    public record TestcaseCaseView(String id, String input, String output) {
    }
}
