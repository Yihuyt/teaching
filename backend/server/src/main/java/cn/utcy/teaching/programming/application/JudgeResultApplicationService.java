package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.error.BadRequestException;
import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.learning.LearningEvent;
import cn.utcy.teaching.shared.learning.LearningEventRecorder;
import cn.utcy.teaching.shared.learning.LearningEventType;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.programming.domain.JudgeJobStatus;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.domain.SubmissionCaseResult;
import cn.utcy.teaching.programming.domain.SubmissionStatus;
import cn.utcy.teaching.programming.infrastructure.JudgeJobMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import cn.utcy.teaching.programming.infrastructure.SubmissionCaseResultMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.regex.Pattern;

@Service
public class JudgeResultApplicationService {
    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final int MAX_CASES = 500;
    private static final int MAX_DETAIL_LENGTH = 2000;
    private static final BigDecimal MAX_SCORE = new BigDecimal("100.00");
    private static final Pattern CASE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Set<SubmissionStatus> NON_CASE_TERMINAL_STATUSES = EnumSet.of(
            SubmissionStatus.COMPILE_ERROR,
            SubmissionStatus.SYSTEM_ERROR,
            SubmissionStatus.WORKER_CRASH_LIMIT);
    private static final Set<SubmissionStatus> CASE_TERMINAL_STATUSES = EnumSet.of(
            SubmissionStatus.ACCEPTED,
            SubmissionStatus.WRONG_ANSWER,
            SubmissionStatus.RUNTIME_ERROR,
            SubmissionStatus.TIME_LIMIT_EXCEEDED,
            SubmissionStatus.MEMORY_LIMIT_EXCEEDED,
            SubmissionStatus.OUTPUT_LIMIT_EXCEEDED);

    private final ProgrammingSubmissionMapper submissions;
    private final JudgeJobMapper jobs;
    private final SubmissionCaseResultMapper caseResults;
    private final ProgrammingProblemMapper problems;
    private final LearningEventRecorder learningEvents;

    public JudgeResultApplicationService(
            ProgrammingSubmissionMapper submissions,
            JudgeJobMapper jobs,
            SubmissionCaseResultMapper caseResults,
            ProgrammingProblemMapper problems,
            LearningEventRecorder learningEvents
    ) {
        this.submissions = submissions;
        this.jobs = jobs;
        this.caseResults = caseResults;
        this.problems = problems;
        this.learningEvents = learningEvents;
    }

    public enum CompletionOutcome {
        APPLIED,
        /** 提交已是同一终态(重复投递),无需再应用 */
        ALREADY_TERMINAL,
        /** 结果来自被重投 / 重判取代的旧执行次数,丢弃(防结果闪回) */
        STALE_ATTEMPT,
        /** 作业已不存在(如课程删除带走提交),丢弃 */
        JOB_MISSING
    }

    public record Completion(CompletionOutcome outcome, String jobStreamRecordId) {
    }

    @Transactional
    public Completion complete(JudgeResult result) {
        validate(result);
        JudgeJob job = jobs.selectForUpdate(result.jobId());
        if (job == null) {
            return new Completion(CompletionOutcome.JOB_MISSING, null);
        }
        if (job.getSubmissionId() != result.submissionId()) {
            throw new ConflictException("判题结果与作业的提交不一致");
        }
        if (job.getAttempt() != result.attempt()) {
            return new Completion(CompletionOutcome.STALE_ATTEMPT, null);
        }
        ProgrammingSubmission submission = submissions.selectById(result.submissionId());
        if (submission == null) {
            throw new IllegalStateException("判题结果引用了不存在的提交：" + result.submissionId());
        }
        if (submission.getStatus().terminal()) {
            if (submission.getStatus() == result.status() && job.getStatus() == JudgeJobStatus.COMPLETED) {
                return new Completion(CompletionOutcome.ALREADY_TERMINAL, job.getStreamRecordId());
            }
            throw new ConflictException("同一提交收到了相互冲突的判题终态");
        }
        submission.complete(
                result.status(),
                result.timeUsedMs(),
                result.memoryUsedKb(),
                result.score(),
                result.detail());
        caseResults.delete(new LambdaQueryWrapper<SubmissionCaseResult>()
                .eq(SubmissionCaseResult::getSubmissionId, submission.getId()));
        for (JudgeResult.CaseResult caseResult : result.cases()) {
            requireMutation(
                    caseResults.insert(new SubmissionCaseResult(
                            null,
                            submission.getId(),
                            caseResult.caseId(),
                            caseResult.status(),
                            caseResult.timeUsedMs(),
                            caseResult.memoryUsedKb(),
                            caseResult.score(),
                            caseResult.detail())),
                    "测试点结果创建未生效");
        }
        job.completed();
        requireMutation(submissions.updateById(submission), "提交判题状态已变化，更新未生效");
        java.util.Map<String, Object> detail = new java.util.LinkedHashMap<>();
        detail.put("submissionId", submission.getId());
        detail.put("status", result.status().name());
        detail.put("accepted", result.status() == SubmissionStatus.ACCEPTED);
        detail.put("score", result.score());
        ProgrammingProblem problem = problems.selectById(submission.getProblemId());
        // 人工重判会 attempt+1 且重投计数清零(attempt-1 ≠ requeueCount),不重复记学情;对账重投沿用首次结果照记
        boolean rejudged = result.attempt() - 1 != job.getRequeueCount();
        if (problem != null && !rejudged) {
            learningEvents.record(new LearningEvent(problem.getCourseId(), submission.getAccountId(),
                    LearningEventType.PROGRAMMING_JUDGED, submission.getProblemId(), detail));
        }
        requireMutation(jobs.updateById(job), "判题任务状态已变化，更新未生效");
        return new Completion(CompletionOutcome.APPLIED, job.getStreamRecordId());
    }

    private void requireMutation(int affectedRows, String message) {
        if (affectedRows != 1) {
            throw new ConflictException(message);
        }
    }

    private void validate(JudgeResult result) {
        if (result == null) {
            throw new BadRequestException("判题结果不能为空");
        }
        if (result.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new BadRequestException("不支持的判题结果 schemaVersion");
        }
        if (result.jobId() == null || result.jobId().isBlank()
                || result.attempt() < 1
                || result.submissionId() < 1
                || result.status() == null
                || result.score() == null
                || result.detail() == null
                || result.cases() == null) {
            throw new BadRequestException("判题结果缺少必填字段");
        }
        try {
            UUID.fromString(result.jobId());
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("判题结果 jobId 必须是 UUID");
        }
        if (!result.status().terminal()) {
            throw new ConflictException("判题结果流只能写入终态结果");
        }
        requireMetrics(result.timeUsedMs(), result.memoryUsedKb(), result.score(), result.detail(), "判题结果");
        if (result.cases().size() > MAX_CASES) {
            throw new BadRequestException("判题结果测试点数量不能超过 " + MAX_CASES);
        }
        if (NON_CASE_TERMINAL_STATUSES.contains(result.status())) {
            if (!result.cases().isEmpty()
                    || result.timeUsedMs() != 0
                    || result.memoryUsedKb() != 0
                    || result.score().compareTo(BigDecimal.ZERO) != 0) {
                throw new BadRequestException("非测试点终态必须不含测试点且总时间、内存和得分均为 0");
            }
            return;
        }
        if (result.cases().isEmpty()) {
            throw new BadRequestException("测试点终态必须至少包含一个测试点结果");
        }
        Set<String> caseIds = new HashSet<>();
        BigDecimal totalCaseScore = BigDecimal.ZERO;
        long totalCaseTime = 0;
        int maximumCaseMemory = 0;
        SubmissionStatus aggregateStatus = SubmissionStatus.ACCEPTED;
        for (JudgeResult.CaseResult caseResult : result.cases()) {
            if (caseResult == null
                    || caseResult.caseId() == null
                    || !CASE_ID.matcher(caseResult.caseId()).matches()
                    || caseResult.status() == null
                    || !CASE_TERMINAL_STATUSES.contains(caseResult.status())
                    || caseResult.score() == null
                    || caseResult.detail() == null) {
                throw new BadRequestException("测试点结果缺少必填字段或状态不属于测试点终态");
            }
            if (!caseIds.add(caseResult.caseId())) {
                throw new BadRequestException("测试点 caseId 不能重复");
            }
            requireMetrics(
                    caseResult.timeUsedMs(),
                    caseResult.memoryUsedKb(),
                    caseResult.score(),
                    caseResult.detail(),
                    "测试点结果");
            totalCaseScore = totalCaseScore.add(caseResult.score());
            totalCaseTime += caseResult.timeUsedMs();
            maximumCaseMemory = Math.max(maximumCaseMemory, caseResult.memoryUsedKb());
            if (aggregateStatus == SubmissionStatus.ACCEPTED
                    && caseResult.status() != SubmissionStatus.ACCEPTED) {
                aggregateStatus = caseResult.status();
            }
        }
        if (result.status() != aggregateStatus) {
            throw new BadRequestException("判题总状态必须等于第一个未通过测试点的状态，全部通过时必须为 ACCEPTED");
        }
        if (totalCaseScore.compareTo(result.score()) != 0) {
            throw new BadRequestException("判题总分必须等于各测试点得分之和");
        }
        if (totalCaseTime != result.timeUsedMs()) {
            throw new BadRequestException("判题总耗时必须等于各测试点耗时之和");
        }
        if (maximumCaseMemory != result.memoryUsedKb()) {
            throw new BadRequestException("判题总内存必须等于各测试点内存最大值");
        }
    }

    private void requireMetrics(
            int timeUsedMs,
            int memoryUsedKb,
            BigDecimal score,
            String detail,
            String name
    ) {
        if (timeUsedMs < 0 || memoryUsedKb < 0) {
            throw new BadRequestException(name + "的时间和内存不能为负数");
        }
        if (score.compareTo(BigDecimal.ZERO) < 0
                || score.compareTo(MAX_SCORE) > 0
                || score.scale() > 2) {
            throw new BadRequestException(name + "的得分必须在 0 到 100 之间且最多保留两位小数");
        }
        if (detail.length() > MAX_DETAIL_LENGTH) {
            throw new BadRequestException(name + "说明不能超过 " + MAX_DETAIL_LENGTH + " 个字符");
        }
    }
}
