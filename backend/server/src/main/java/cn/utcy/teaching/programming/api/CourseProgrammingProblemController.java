package cn.utcy.teaching.programming.api;

import cn.utcy.teaching.programming.application.ProblemPackageImportApplicationService;
import cn.utcy.teaching.programming.application.ProgrammingProblemApplicationService;
import cn.utcy.teaching.programming.application.ProgrammingProblemApplicationService.CourseProgrammingProblemView;
import cn.utcy.teaching.programming.application.ProgrammingProblemApplicationService.ProblemDetailView;
import cn.utcy.teaching.programming.application.ProgrammingProblemApplicationService.ProblemManagementDetailView;
import cn.utcy.teaching.programming.application.RejudgeApplicationService;
import cn.utcy.teaching.programming.application.RejudgeApplicationService.RejudgeSummary;
import cn.utcy.teaching.programming.application.SubmissionApplicationService;
import cn.utcy.teaching.programming.application.SubmissionApplicationService.StudentSubmissionResultView;
import cn.utcy.teaching.programming.application.SubmissionApplicationService.SubmissionDetailView;
import cn.utcy.teaching.programming.application.SubmissionApplicationService.SubmissionSummaryView;
import cn.utcy.teaching.programming.application.SubmissionApplicationService.SubmissionView;
import cn.utcy.teaching.programming.application.TestcaseApplicationService;
import cn.utcy.teaching.programming.application.TestcaseApplicationService.ManualTestcaseCommand;
import cn.utcy.teaching.programming.application.TestcaseApplicationService.TestcaseCaseView;
import cn.utcy.teaching.programming.domain.ProblemDifficulty;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import cn.utcy.teaching.programming.domain.SubmissionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;

@Validated
@RestController
@RequestMapping("/api/v1/courses/{courseId}/programming-problems")
public class CourseProgrammingProblemController {
    private final ProgrammingProblemApplicationService problems;
    private final ProblemPackageImportApplicationService packageImports;
    private final TestcaseApplicationService testcases;
    private final SubmissionApplicationService submissions;
    private final RejudgeApplicationService rejudge;

    public CourseProgrammingProblemController(
            ProgrammingProblemApplicationService problems,
            ProblemPackageImportApplicationService packageImports,
            TestcaseApplicationService testcases,
            SubmissionApplicationService submissions,
            RejudgeApplicationService rejudge
    ) {
        this.problems = problems;
        this.packageImports = packageImports;
        this.testcases = testcases;
        this.submissions = submissions;
        this.rejudge = rejudge;
    }

    // ---- 教师端 ----

    @GetMapping
    public List<CourseProgrammingProblemView> list(@PathVariable @Min(1) long courseId) {
        return problems.list(courseId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProblemManagementDetailView create(
            @PathVariable @Min(1) long courseId,
            @Valid @RequestBody ProblemDraftRequest request
    ) {
        return problems.create(courseId, request.toDraft());
    }

    @PostMapping(value = "/package-imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ProblemManagementDetailView importPackage(
            @PathVariable @Min(1) long courseId,
            @RequestPart("file") MultipartFile file,
            @RequestParam @NotNull ProblemDifficulty difficulty
    ) {
        return packageImports.importPackage(courseId, file, difficulty);
    }

    @GetMapping("/{problemId}/management")
    public ProblemManagementDetailView getForManagement(
            @PathVariable @Min(1) long courseId, @PathVariable @Min(1) long problemId
    ) {
        return problems.getForManagement(courseId, problemId);
    }

    @PutMapping("/{problemId}")
    public ProblemManagementDetailView update(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long problemId,
            @Valid @RequestBody ProblemDraftRequest request
    ) {
        return problems.update(courseId, problemId, request.toDraft());
    }

    @DeleteMapping("/{problemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long problemId) {
        problems.delete(courseId, List.of(problemId));
    }

    @PostMapping("/deletions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMany(@PathVariable @Min(1) long courseId, @Valid @RequestBody CourseProblemDeletionRequest request) {
        problems.delete(courseId, request.ids());
    }

    public record CourseProblemDeletionRequest(@NotEmpty List<@Min(1) Long> ids) {
    }

    @GetMapping("/{problemId}/testcases")
    public List<TestcaseCaseView> testcases(
            @PathVariable @Min(1) long courseId, @PathVariable @Min(1) long problemId
    ) {
        return testcases.listCases(courseId, problemId);
    }

    @PostMapping("/{problemId}/manual-testcase-packages")
    @ResponseStatus(HttpStatus.CREATED)
    public ProblemManagementDetailView createManualTestcasePackage(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long problemId,
            @Valid @RequestBody ManualTestcasePackageRequest request
    ) {
        return testcases.createManualPackage(courseId, problemId, request.toCommands());
    }

    @DeleteMapping("/{problemId}/testcase")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTestcase(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long problemId) {
        testcases.delete(courseId, problemId);
    }

    public record ManualTestcasePackageRequest(
            @NotEmpty @Size(max = 500) List<@Valid ManualTestcaseRequest> cases
    ) {
        public List<ManualTestcaseCommand> toCommands() {
            return cases.stream().map(value -> new ManualTestcaseCommand(value.input(), value.output())).toList();
        }
    }

    public record ManualTestcaseRequest(
            @NotNull @Size(max = 67_108_864) String input,
            @NotNull @Size(max = 67_108_864) String output
    ) {
    }

    @GetMapping("/{problemId}/results")
    public List<StudentSubmissionResultView> results(
            @PathVariable @Min(1) long courseId, @PathVariable @Min(1) long problemId
    ) {
        return submissions.results(courseId, problemId);
    }

    @GetMapping("/{problemId}/results/{accountId}/submissions")
    public List<SubmissionSummaryView> studentSubmissions(
            @PathVariable @Min(1) long courseId, @PathVariable @Min(1) long problemId,
            @PathVariable @Min(1) long accountId
    ) {
        return submissions.studentSubmissions(courseId, problemId, accountId);
    }

    @PostMapping("/{problemId}/submissions/{submissionId}/rejudge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void rejudgeSubmission(
            @PathVariable @Min(1) long courseId, @PathVariable @Min(1) long problemId,
            @PathVariable @Min(1) long submissionId
    ) {
        rejudge.rejudgeSubmission(courseId, problemId, submissionId);
    }

    @PostMapping("/{problemId}/rejudges")
    public RejudgeSummary rejudgeProblem(
            @PathVariable @Min(1) long courseId, @PathVariable @Min(1) long problemId,
            @Valid @RequestBody RejudgeProblemRequest request
    ) {
        return rejudge.rejudgeProblem(
                courseId, problemId,
                request.statuses() == null ? Set.of() : Set.copyOf(request.statuses()));
    }

    public record RejudgeProblemRequest(
            @Schema(nullable = true) @Size(max = 10) List<SubmissionStatus> statuses
    ) {
    }

    // ---- 学生端 ----

    @GetMapping("/{problemId}")
    public ProblemDetailView get(@PathVariable @Min(1) long courseId, @PathVariable @Min(1) long problemId) {
        return problems.get(courseId, problemId);
    }

    @PostMapping("/{problemId}/submissions")
    @ResponseStatus(HttpStatus.CREATED)
    public SubmissionView submit(
            @PathVariable @Min(1) long courseId,
            @PathVariable @Min(1) long problemId,
            @Valid @RequestBody SubmitRequest request
    ) {
        return submissions.submit(courseId, problemId, request.language(), request.sourceCode());
    }

    public record SubmitRequest(
            @NotNull ProgrammingLanguage language,
            @NotBlank @Size(max = 100_000, message = "源代码不能超过 100000 个字符") String sourceCode
    ) {
    }

    @GetMapping("/{problemId}/submissions")
    public List<SubmissionSummaryView> mySubmissions(
            @PathVariable @Min(1) long courseId, @PathVariable @Min(1) long problemId
    ) {
        return submissions.listMine(courseId, problemId);
    }

    @GetMapping("/{problemId}/submissions/{submissionId}")
    public SubmissionDetailView submission(
            @PathVariable @Min(1) long courseId, @PathVariable @Min(1) long problemId,
            @PathVariable @Min(1) long submissionId
    ) {
        return submissions.get(courseId, problemId, submissionId);
    }
}
