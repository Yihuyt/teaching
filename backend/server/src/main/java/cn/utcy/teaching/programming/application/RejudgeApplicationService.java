package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.shared.error.ConflictException;
import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.error.NotFoundException;
import cn.utcy.teaching.course.application.CourseAccess;
import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.domain.SubmissionCaseResult;
import cn.utcy.teaching.programming.domain.SubmissionStatus;
import cn.utcy.teaching.programming.infrastructure.JudgeJobMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import cn.utcy.teaching.programming.infrastructure.SubmissionCaseResultMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * 课程管理者重判:提交复位 QUEUED、作业按 attempt+1 复位 pending(重投计数清零),
 * 之后完全复用 发布 → worker → 结果落库 通道;旧在途结果被 attempt 围栏丢弃,不会闪回。
 * 评测进行中的提交拒绝重判。
 */
@Service
public class RejudgeApplicationService {

    private static final Logger log = LoggerFactory.getLogger(RejudgeApplicationService.class);
    private static final int BATCH_LIMIT = 500;

    private final ProgrammingSubmissionMapper submissions;
    private final JudgeJobMapper jobs;
    private final SubmissionCaseResultMapper caseResults;
    private final ProgrammingProblemMapper problems;
    private final CurrentActor currentActor;
    private final CourseAccess courseAccess;
    private final TransactionTemplate transactions;

    public RejudgeApplicationService(
            ProgrammingSubmissionMapper submissions,
            JudgeJobMapper jobs,
            SubmissionCaseResultMapper caseResults,
            ProgrammingProblemMapper problems,
            CurrentActor currentActor,
            CourseAccess courseAccess,
            TransactionTemplate transactions
    ) {
        this.submissions = submissions;
        this.jobs = jobs;
        this.caseResults = caseResults;
        this.problems = problems;
        this.currentActor = currentActor;
        this.courseAccess = courseAccess;
        this.transactions = transactions;
    }

    public record RejudgeSummary(int requeued, int skippedInProgress, int failed) {
    }

    @Transactional
    public void rejudgeSubmission(long courseId, long problemId, long submissionId) {
        requireProblem(courseId, problemId);
        ProgrammingSubmission submission = submissions.selectById(submissionId);
        if (submission == null || submission.getProblemId() != problemId) {
            throw new NotFoundException("提交不存在");
        }
        rejudgeOne(submissionId);
    }

    /** 整题重判:逐条独立事务,单条失败不拖垮整批;statuses 为空 = 全部终态提交 */
    public RejudgeSummary rejudgeProblem(long courseId, long problemId, Set<SubmissionStatus> statuses) {
        requireProblem(courseId, problemId);
        LambdaQueryWrapper<ProgrammingSubmission> query = new LambdaQueryWrapper<ProgrammingSubmission>()
                .eq(ProgrammingSubmission::getProblemId, problemId)
                .orderByAsc(ProgrammingSubmission::getId)
                .last("LIMIT " + BATCH_LIMIT);
        if (statuses != null && !statuses.isEmpty()) {
            query.in(ProgrammingSubmission::getStatus, statuses);
        }
        List<Long> submissionIds = submissions.selectList(query).stream()
                .map(ProgrammingSubmission::getId)
                .toList();
        int requeued = 0;
        int skippedInProgress = 0;
        int failed = 0;
        for (Long submissionId : submissionIds) {
            try {
                transactions.executeWithoutResult(status -> rejudgeOne(submissionId));
                requeued += 1;
            } catch (ConflictException exception) {
                skippedInProgress += 1;
            } catch (RuntimeException exception) {
                failed += 1;
                log.error("重判失败，跳过并继续：submissionId={}", submissionId, exception);
            }
        }
        return new RejudgeSummary(requeued, skippedInProgress, failed);
    }

    private void requireProblem(long courseId, long problemId) {
        courseAccess.requireManagementAccess(courseId, currentActor.require());
        ProgrammingProblem problem = problems.selectById(problemId);
        if (problem == null || problem.getCourseId() != courseId) {
            throw new NotFoundException("课程中不存在该编程题");
        }
    }

    private void rejudgeOne(long submissionId) {
        // 锁序与结果落库一致:先锁作业行,再动提交,避免与结果消费死锁
        JudgeJob job = jobs.selectBySubmissionForUpdate(submissionId);
        if (job == null) {
            throw new NotFoundException("提交不存在或没有判题作业");
        }
        ProgrammingSubmission submission = submissions.selectById(submissionId);
        if (submission == null) {
            throw new NotFoundException("提交不存在");
        }
        if (!submission.getStatus().terminal()) {
            throw new ConflictException("评测进行中，不能重判");
        }
        caseResults.delete(new LambdaQueryWrapper<SubmissionCaseResult>()
                .eq(SubmissionCaseResult::getSubmissionId, submissionId));
        if (submissions.resetForRejudge(submissionId, Instant.now()) != 1) {
            throw new IllegalStateException("提交状态已变化，重判未生效：" + submissionId);
        }
        // 作业复位:attempt+1(旧在途结果的防闪回围栏),重投计数清零(人工介入重新起算)
        if (jobs.resetForRepublish(job.getId(), job.getAttempt() + 1, 0, job.getStatus().name().toLowerCase()) != 1) {
            throw new IllegalStateException("判题作业状态已变化，重判未生效：" + job.getId());
        }
        log.warn("提交已重判：submissionId={}，attempt={}", submissionId, job.getAttempt() + 1);
    }
}
