package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.course.CourseContentDeletionGuard;
import cn.utcy.teaching.shared.course.CourseOutlineItemType;
import cn.utcy.teaching.shared.course.CourseOutlineLinks;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.domain.ProgrammingProblemProvenance;
import cn.utcy.teaching.programming.domain.ProgrammingProblemSample;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemProvenanceMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemSampleMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ProgrammingProblemApplicationService {
    private static final TypeReference<Set<ProgrammingLanguage>> LANGUAGE_SET = new TypeReference<>() {
    };
    private static final TypeReference<List<ProblemSourceView>> SOURCE_LIST = new TypeReference<>() {
    };
    private static final int MAX_SAMPLE_BYTES = 1024 * 1024;

    private final ProgrammingProblemMapper problems;
    private final ProgrammingProblemSampleMapper samples;
    private final ProgrammingProblemProvenanceMapper provenance;
    private final ProgrammingSubmissionMapper submissions;
    private final CurrentActor currentActor;
    private final ObjectMapper objectMapper;
    private final CourseOutlineLinks courseOutlineLinks;
    private final CourseAccess courseAccess;
    private final ProgrammingPurger purger;
    private final List<CourseContentDeletionGuard> deletionGuards;

    public ProgrammingProblemApplicationService(
            ProgrammingProblemMapper problems,
            ProgrammingProblemSampleMapper samples,
            ProgrammingProblemProvenanceMapper provenance,
            ProgrammingSubmissionMapper submissions,
            CurrentActor currentActor,
            ObjectMapper objectMapper,
            CourseOutlineLinks courseOutlineLinks,
            CourseAccess courseAccess,
            ProgrammingPurger purger,
            List<CourseContentDeletionGuard> deletionGuards
    ) {
        this.problems = problems;
        this.samples = samples;
        this.provenance = provenance;
        this.submissions = submissions;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
        this.courseOutlineLinks = courseOutlineLinks;
        this.courseAccess = courseAccess;
        this.purger = purger;
        this.deletionGuards = List.copyOf(deletionGuards);
    }

    @Transactional(readOnly = true)
    public List<CourseProgrammingProblemView> list(long courseId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        return problems.selectList(new LambdaQueryWrapper<ProgrammingProblem>()
                        .eq(ProgrammingProblem::getCourseId, courseId)
                        .orderByDesc(ProgrammingProblem::getUpdatedAt)
                        .orderByDesc(ProgrammingProblem::getId))
                .stream()
                .map(problem -> new CourseProgrammingProblemView(
                        problem.getId(), problem.getTitle(), problem.getDifficulty(),
                        problem.hasConfirmedTestcase(), problem.getUpdatedAt()))
                .toList();
    }

    @Transactional
    public ProblemManagementDetailView create(long courseId, ProblemDraft draft) {
        Actor actor = currentActor.require();
        courseAccess.requireManagementAccess(courseId, actor);
        validateLanguages(draft.languages());
        List<ProblemSampleCommand> normalizedSamples = validateSamples(draft.samples());
        ProgrammingProblem problem = ProgrammingProblem.create(
                actor.userId(), courseId, draft.title().trim(), draft.statementMarkdown(),
                draft.difficulty(), draft.timeLimitMs(), draft.memoryLimitMb(), draft.outputLimitKb(),
                writeLanguages(draft.languages()));
        requireMutation(problems.insert(problem), "编程题创建未生效");
        replaceSamples(problem.getId(), normalizedSamples);
        return managementDetailView(problem);
    }

    @Transactional(readOnly = true)
    public ProblemDetailView get(long courseId, long problemId) {
        return detailView(requireLearnableProblem(courseId, problemId, false));
    }

    @Transactional(readOnly = true)
    public ProblemManagementDetailView getForManagement(long courseId, long problemId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        return managementDetailView(requireProblem(courseId, problemId));
    }

    @Transactional
    public ProblemManagementDetailView update(long courseId, long problemId, ProblemDraft draft) {
        ProgrammingProblem problem = requireProblemForUpdate(courseId, problemId);
        validateLanguages(draft.languages());
        List<ProblemSampleView> currentSamples = sampleViews(problemId);
        List<ProblemSampleCommand> normalizedSamples = validateSamples(draft.samples());
        boolean judgeConfigChanged = judgeConfigChanged(problem, draft, currentSamples, normalizedSamples);
        if (isImported(problemId) && judgeConfigChanged) {
            throw new ConflictException(
                    "标准题目包导入的题面与评测配置不能直接修改；无引用且无提交时，请删除原题后重新导入修订包");
        }
        if (hasSubmissions(problemId) && (judgeConfigChanged || problem.getDifficulty() != draft.difficulty())) {
            throw new ConflictException("编程题产生提交后不能修改题面、难度或评测配置");
        }
        problem.update(
                draft.title().trim(), draft.statementMarkdown(), draft.difficulty(),
                draft.timeLimitMs(), draft.memoryLimitMb(), draft.outputLimitKb(), writeLanguages(draft.languages()));
        requireMutation(problems.updateById(problem), "编程题状态已变化，更新未生效");
        replaceSamples(problemId, normalizedSamples);
        return managementDetailView(problem);
    }

    @Transactional
    public void delete(long courseId, List<Long> problemIds) {
        List<Long> locked = new ArrayList<>();
        for (Long problemId : problemIds) {
            ProgrammingProblem problem = requireProblemForUpdate(courseId, problemId);
            courseOutlineLinks.unlink(courseId, CourseOutlineItemType.PROGRAMMING_PROBLEM, problemId);
            deletionGuards.forEach(guard -> guard.beforeContentDeleted(
                    courseId, CourseOutlineItemType.PROGRAMMING_PROBLEM, problemId));
            locked.add(problem.getId());
        }
        purger.purgeProblems(locked);
    }

    @Transactional(readOnly = true)
    public boolean hasSubmissions(long courseId, long problemId) {
        ProgrammingProblem problem = problems.selectById(problemId);
        return problem != null && problem.getCourseId() == courseId && hasSubmissions(problemId);
    }

    // ---- 解析与门禁(同模块其他服务复用) ----

    ProgrammingProblem requireProblem(long courseId, long problemId) {
        return requireInCourse(courseId, problems.selectById(problemId));
    }

    ProgrammingProblem requireProblemForUpdate(long courseId, long problemId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        return requireInCourse(courseId, problems.selectForUpdate(problemId));
    }

    ProgrammingProblem requireLearnableProblem(long courseId, long problemId, boolean forUpdate) {
        courseAccess.requireLearningAccess(courseId, currentActor.require());
        if (!courseOutlineLinks.isLinked(courseId, CourseOutlineItemType.PROGRAMMING_PROBLEM, problemId)) {
            throw new NotFoundException("课程内容中不存在该编程题");
        }
        return requireInCourse(
                courseId, forUpdate ? problems.selectForUpdate(problemId) : problems.selectById(problemId));
    }

    private ProgrammingProblem requireInCourse(long courseId, ProgrammingProblem problem) {
        if (problem == null || problem.getCourseId() != courseId) {
            throw new NotFoundException("课程中不存在该编程题");
        }
        return problem;
    }

    ProgrammingProblem requireReferenced(long problemId) {
        ProgrammingProblem problem = problems.selectById(problemId);
        if (problem == null) {
            throw new IllegalStateException("提交记录引用的编程题不存在");
        }
        return problem;
    }

    boolean hasSubmissions(long problemId) {
        return submissions.exists(new LambdaQueryWrapper<ProgrammingSubmission>()
                .eq(ProgrammingSubmission::getProblemId, problemId));
    }

    boolean isImported(long problemId) {
        return provenance.selectById(problemId) != null;
    }

    void requireMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new ConflictException(message);
        }
    }

    Set<ProgrammingLanguage> readLanguages(String value) {
        try {
            return objectMapper.readValue(value, LANGUAGE_SET);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的编程语言集合不是有效 JSON", exception);
        }
    }

    // ---- 核心规则 ----

    private void validateLanguages(Set<ProgrammingLanguage> languages) {
        if (languages == null || languages.isEmpty()) {
            throw new BadRequestException("至少允许一种编程语言");
        }
    }

    private String writeLanguages(Set<ProgrammingLanguage> languages) {
        try {
            return objectMapper.writeValueAsString(EnumSet.copyOf(languages));
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("编程语言集合格式不正确");
        }
    }

    private void replaceSamples(long problemId, List<ProblemSampleCommand> normalized) {
        samples.deleteByProblemId(problemId);
        for (int index = 0; index < normalized.size(); index++) {
            ProblemSampleCommand sample = normalized.get(index);
            requireMutation(
                    samples.insert(ProgrammingProblemSample.create(problemId, index + 1, sample.input(), sample.output())),
                    "编程题公开样例保存未生效");
        }
    }

    private List<ProblemSampleCommand> validateSamples(List<ProblemSampleCommand> commands) {
        if (commands == null) {
            throw new BadRequestException("公开样例列表不能为空");
        }
        if (commands.size() > 50) {
            throw new BadRequestException("公开样例不能超过 50 组");
        }
        return commands.stream().map(sample -> {
            if (sample == null || sample.input() == null || sample.output() == null) {
                throw new BadRequestException("公开样例输入和输出不能为空");
            }
            if (sample.input().getBytes(StandardCharsets.UTF_8).length > MAX_SAMPLE_BYTES
                    || sample.output().getBytes(StandardCharsets.UTF_8).length > MAX_SAMPLE_BYTES) {
                throw new BadRequestException("单个公开样例输入或输出不能超过 1 MiB");
            }
            return new ProblemSampleCommand(sample.input(), sample.output());
        }).toList();
    }

    /** 题面、限制、语言、样例任一变化(难度不算:它不影响评测) */
    private boolean judgeConfigChanged(
            ProgrammingProblem problem,
            ProblemDraft draft,
            List<ProblemSampleView> currentSamples,
            List<ProblemSampleCommand> requestedSamples
    ) {
        return !problem.getTitle().equals(draft.title().trim())
                || !problem.getStatementMarkdown().equals(draft.statementMarkdown())
                || problem.getTimeLimitMs() != draft.timeLimitMs()
                || problem.getMemoryLimitMb() != draft.memoryLimitMb()
                || problem.getOutputLimitKb() != draft.outputLimitKb()
                || !readLanguages(problem.getLanguagesJson()).equals(draft.languages())
                || !currentSamples.stream()
                .map(sample -> new ProblemSampleCommand(sample.input(), sample.output()))
                .toList()
                .equals(requestedSamples);
    }

    // ---- 视图 ----

    ProblemManagementDetailView managementDetailView(ProgrammingProblem problem) {
        return new ProblemManagementDetailView(
                detailView(problem), problem.getTestcaseSha256(),
                problem.getTestcaseSizeBytes(), problem.getTestcaseConfirmedAt(),
                submissions.selectCount(new LambdaQueryWrapper<ProgrammingSubmission>()
                        .eq(ProgrammingSubmission::getProblemId, problem.getId())));
    }

    private ProblemView view(ProgrammingProblem problem) {
        return new ProblemView(
                problem.getId(), problem.getCourseId(), problem.getOwnerId(), problem.getTitle(),
                problem.getDifficulty(), problem.getTimeLimitMs(), problem.getMemoryLimitMb(),
                problem.getOutputLimitKb(), readLanguages(problem.getLanguagesJson()),
                problem.getCreatedAt(), problem.getUpdatedAt());
    }

    private ProblemDetailView detailView(ProgrammingProblem problem) {
        return new ProblemDetailView(
                view(problem), problem.getStatementMarkdown(), sampleViews(problem.getId()),
                provenanceView(provenance.selectById(problem.getId())));
    }

    private List<ProblemSampleView> sampleViews(long problemId) {
        return samples.selectList(new LambdaQueryWrapper<ProgrammingProblemSample>()
                        .eq(ProgrammingProblemSample::getProblemId, problemId)
                        .orderByAsc(ProgrammingProblemSample::getPosition, ProgrammingProblemSample::getId))
                .stream()
                .map(sample -> new ProblemSampleView(sample.getPosition(), sample.getInputText(), sample.getOutputText()))
                .toList();
    }

    private ProblemProvenanceView provenanceView(ProgrammingProblemProvenance value) {
        if (value == null) {
            return null;
        }
        try {
            List<ProblemSourceView> sources = value.getSourcesJson() == null
                    ? List.of()
                    : objectMapper.readValue(value.getSourcesJson(), SOURCE_LIST);
            ProblemCreditsView credits = readCredits(value.getCreditsJson());
            return new ProblemProvenanceView(
                    value.getProblemFormatVersion(), value.getPackageUuid(), value.getPackageVersion(),
                    sources, credits, value.getLicenseCode(), value.getRightsOwner(),
                    value.getSourcePackageSha256(), value.getSourcePackageSizeBytes(), value.getImportedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("数据库中的题目包来源元数据不是有效 JSON", exception);
        }
    }

    private ProblemCreditsView readCredits(String value) throws JsonProcessingException {
        if (value == null) {
            return null;
        }
        StoredProblemCredits stored = objectMapper.readValue(value, StoredProblemCredits.class);
        return new ProblemCreditsView(
                immutableOrEmpty(stored.authors()),
                immutableOrEmpty(stored.contributors()),
                immutableOrEmpty(stored.testers()),
                stored.translators() == null ? Map.of() : Map.copyOf(stored.translators()),
                immutableOrEmpty(stored.packagers()),
                immutableOrEmpty(stored.acknowledgements()));
    }

    private List<ProblemPersonView> immutableOrEmpty(List<ProblemPersonView> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    public record ProblemDraft(
            String title,
            String statementMarkdown,
            ProblemDifficulty difficulty,
            int timeLimitMs,
            int memoryLimitMb,
            int outputLimitKb,
            Set<ProgrammingLanguage> languages,
            List<ProblemSampleCommand> samples
    ) {
    }

    public record CourseProgrammingProblemView(
            long id,
            String title,
            ProblemDifficulty difficulty,
            /** 已配置测试数据才能加入课程内容 */
            boolean testcaseConfirmed,
            Instant updatedAt
    ) {
    }

    public record ProblemView(
            long id,
            long courseId,
            long ownerId,
            String title,
            ProblemDifficulty difficulty,
            int timeLimitMs,
            int memoryLimitMb,
            int outputLimitKb,
            Set<ProgrammingLanguage> languages,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record ProblemDetailView(
            ProblemView problem,
            String statementMarkdown,
            List<ProblemSampleView> samples,
            @Schema(nullable = true) ProblemProvenanceView provenance
    ) {
    }

    public record ProblemSampleView(int position, String input, String output) {
    }

    public record ProblemSampleCommand(String input, String output) {
    }

    public record ProblemSourceView(String name, @Schema(nullable = true) String url) {
    }

    public record ProblemPersonView(
            String name,
            @Schema(nullable = true) String email,
            @Schema(nullable = true) String orcid,
            @Schema(nullable = true) String kattis
    ) {
    }

    public record ProblemCreditsView(
            List<ProblemPersonView> authors,
            List<ProblemPersonView> contributors,
            List<ProblemPersonView> testers,
            Map<String, List<ProblemPersonView>> translators,
            List<ProblemPersonView> packagers,
            List<ProblemPersonView> acknowledgements
    ) {
    }

    public record ProblemProvenanceView(
            String problemFormatVersion,
            String packageUuid,
            @Schema(nullable = true) String packageVersion,
            List<ProblemSourceView> sources,
            @Schema(nullable = true) ProblemCreditsView credits,
            String licenseCode,
            @Schema(nullable = true) String rightsOwner,
            String sourcePackageSha256,
            long sourcePackageSizeBytes,
            Instant importedAt
    ) {
    }

    public record ProblemManagementDetailView(
            ProblemDetailView details,
            @Schema(nullable = true) String testcaseSha256,
            @Schema(nullable = true) Long testcaseSizeBytes,
            @Schema(nullable = true) Instant testcaseConfirmedAt,
            /** 提交次数:有提交后题面与测试数据锁定 */
            long submissionCount
    ) {
    }

    private record StoredProblemCredits(
            List<ProblemPersonView> authors,
            List<ProblemPersonView> contributors,
            List<ProblemPersonView> testers,
            Map<String, List<ProblemPersonView>> translators,
            List<ProblemPersonView> packagers,
            List<ProblemPersonView> acknowledgements
    ) {
    }
}
