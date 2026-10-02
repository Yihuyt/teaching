package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.actor.Actor;
import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.identity.application.AccountDirectory;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.programming.domain.ProgrammingLanguage;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.domain.SubmissionCaseResult;
import cn.utcy.teaching.programming.domain.SubmissionStatus;
import cn.utcy.teaching.programming.infrastructure.AccountSubmissionSummary;
import cn.utcy.teaching.programming.infrastructure.JudgeJobMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import cn.utcy.teaching.programming.infrastructure.SubmissionCaseResultMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class SubmissionApplicationService {
    private final ProgrammingProblemApplicationService problems;
    private final ProgrammingSubmissionMapper submissions;
    private final SubmissionCaseResultMapper caseResults;
    private final JudgeJobMapper jobs;
    private final AccountDirectory accounts;
    private final CurrentActor currentActor;
    private final CourseAccess courseAccess;

    public SubmissionApplicationService(
            ProgrammingProblemApplicationService problems,
            ProgrammingSubmissionMapper submissions,
            SubmissionCaseResultMapper caseResults,
            JudgeJobMapper jobs,
            AccountDirectory accounts,
            CurrentActor currentActor,
            CourseAccess courseAccess
    ) {
        this.problems = problems;
        this.submissions = submissions;
        this.caseResults = caseResults;
        this.jobs = jobs;
        this.accounts = accounts;
        this.currentActor = currentActor;
        this.courseAccess = courseAccess;
    }

    @Transactional
    public SubmissionView submit(long courseId, long problemId, ProgrammingLanguage language, String sourceCode) {
        ProgrammingProblem problem = problems.requireLearnableProblem(courseId, problemId, true);
        if (!problem.hasConfirmedTestcase()) {
            throw new ConflictException("编程题尚未配置测试数据");
        }
        if (!problems.readLanguages(problem.getLanguagesJson()).contains(language)) {
            throw new BadRequestException("该题不允许使用所选编程语言");
        }
        Actor actor = currentActor.require();
        ProgrammingSubmission submission = ProgrammingSubmission.create(
                problem.getId(), actor.userId(), language, sourceCode);
        requireMutation(submissions.insert(submission), "提交记录创建未生效");
        requireMutation(jobs.insert(JudgeJob.pending(submission.getId())), "判题任务创建未生效");
        return view(submission, problem);
    }

    @Transactional(readOnly = true)
    public List<SubmissionSummaryView> listMine(long courseId, long problemId) {
        problems.requireLearnableProblem(courseId, problemId, false);
        return summaries(problemId, currentActor.require().userId());
    }

    @Transactional(readOnly = true)
    public SubmissionDetailView get(long courseId, long problemId, long submissionId) {
        Actor actor = currentActor.require();
        ProgrammingProblem problem = problems.requireProblem(courseId, problemId);
        ProgrammingSubmission submission = submissions.selectById(submissionId);
        if (submission == null || submission.getProblemId() != problemId) {
            throw new NotFoundException("提交记录不存在");
        }
        if (actor.userId() == submission.getAccountId()) {
            courseAccess.requireLearningAccess(courseId, actor);
        } else {
            courseAccess.requireManagementAccess(courseId, actor);
        }
        List<CaseResultView> cases = caseResults.selectList(
                        new LambdaQueryWrapper<SubmissionCaseResult>()
                                .eq(SubmissionCaseResult::getSubmissionId, submissionId)
                                .orderByAsc(SubmissionCaseResult::getId))
                .stream()
                .map(result -> new CaseResultView(
                        result.getCaseId(),
                        result.getStatus(),
                        result.getTimeUsedMs(),
                        result.getMemoryUsedKb(),
                        result.getScore(),
                        result.getDetail()))
                .toList();
        return new SubmissionDetailView(view(submission, problem), cases);
    }

    @Transactional(readOnly = true)
    public List<StudentSubmissionResultView> results(long courseId, long problemId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        problems.requireProblem(courseId, problemId);
        List<StudentSubmissionResultView> rows = new ArrayList<>();
        for (AccountSubmissionSummary row : submissions.summarizeByAccount(problemId)) {
            AccountDirectory.AccountSummary account = accounts.require(row.accountId());
            rows.add(new StudentSubmissionResultView(row.accountId(), account.name(), row.submissionCount(),
                    row.accepted(), row.bestScore(), row.lastSubmittedAt()));
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public List<SubmissionSummaryView> studentSubmissions(long courseId, long problemId, long accountId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        problems.requireProblem(courseId, problemId);
        return summaries(problemId, accountId);
    }

    private List<SubmissionSummaryView> summaries(long problemId, long accountId) {
        return submissions.selectList(new LambdaQueryWrapper<ProgrammingSubmission>()
                        .eq(ProgrammingSubmission::getProblemId, problemId)
                        .eq(ProgrammingSubmission::getAccountId, accountId)
                        .orderByDesc(ProgrammingSubmission::getSubmittedAt))
                .stream()
                .map(this::summaryView)
                .toList();
    }

    private void requireMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new ConflictException(message);
        }
    }

    private SubmissionSummaryView summaryView(ProgrammingSubmission submission) {
        return new SubmissionSummaryView(
                submission.getId(),
                submission.getProblemId(),
                submission.getAccountId(),
                submission.getLanguage(),
                submission.getStatus(),
                submission.getTimeUsedMs(),
                submission.getMemoryUsedKb(),
                submission.getScore(),
                submission.getResultDetail(),
                submission.getSubmittedAt(),
                submission.getCompletedAt());
    }

    private SubmissionView view(ProgrammingSubmission submission, ProgrammingProblem problem) {
        return new SubmissionView(
                submission.getId(),
                submission.getProblemId(),
                problem.getTitle(),
                problem.getCourseId(),
                submission.getAccountId(),
                submission.getLanguage(),
                submission.getSourceCode(),
                submission.getStatus(),
                submission.getTimeUsedMs(),
                submission.getMemoryUsedKb(),
                submission.getScore(),
                submission.getResultDetail(),
                submission.getSubmittedAt(),
                submission.getCompletedAt());
    }

    public record SubmissionView(
            long id,
            long problemId,
            String problemTitle,
            long courseId,
            long accountId,
            ProgrammingLanguage language,
            String sourceCode,
            SubmissionStatus status,
            @Schema(nullable = true) Integer timeUsedMs,
            @Schema(nullable = true) Integer memoryUsedKb,
            @Schema(nullable = true) BigDecimal score,
            @Schema(nullable = true) String resultDetail,
            Instant submittedAt,
            @Schema(nullable = true) Instant completedAt
    ) {
    }

    public record SubmissionSummaryView(
            long id,
            long problemId,
            long accountId,
            ProgrammingLanguage language,
            SubmissionStatus status,
            @Schema(nullable = true) Integer timeUsedMs,
            @Schema(nullable = true) Integer memoryUsedKb,
            @Schema(nullable = true) BigDecimal score,
            @Schema(nullable = true) String resultDetail,
            Instant submittedAt,
            @Schema(nullable = true) Instant completedAt
    ) {
    }

    public record SubmissionDetailView(
            SubmissionView submission,
            List<CaseResultView> cases
    ) {
    }

    public record CaseResultView(
            String caseId,
            SubmissionStatus status,
            int timeUsedMs,
            int memoryUsedKb,
            @Schema(nullable = true) BigDecimal score,
            String detail
    ) {
    }

    public record StudentSubmissionResultView(
            long accountId,
            String accountName,
            int submissionCount,
            boolean accepted,
            @Schema(nullable = true) Double bestScore,
            Instant lastSubmittedAt
    ) {
    }
}
