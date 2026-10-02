package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.shared.storage.ObjectStorageDeletionQueue;
import cn.utcy.teaching.programming.domain.JudgeJobStatus;
import cn.utcy.teaching.programming.domain.ProgrammingProblem;
import cn.utcy.teaching.programming.domain.ProgrammingProblemProvenance;
import cn.utcy.teaching.programming.domain.ProgrammingProblemSample;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.domain.SubmissionCaseResult;
import cn.utcy.teaching.programming.infrastructure.JudgeJobMapper;
import cn.utcy.teaching.programming.infrastructure.JudgeStreamReclaimer;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemProvenanceMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingProblemSampleMapper;
import cn.utcy.teaching.programming.infrastructure.ProgrammingSubmissionMapper;
import cn.utcy.teaching.programming.infrastructure.SubmissionCaseResultMapper;
import cn.utcy.teaching.programming.infrastructure.TestcaseOssProperties;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * 编程题及其从属行的显式删除(数据库不设外键,子行由这里逐表清理):
 * 提交 → 测试点结果、判题作业;题 → 样例、题包来源、提交。删题 / 删课程共用,必须在业务事务内、题行已加锁后调用。
 * 在途判题任务在事务提交后尽力回收 Redis 痕迹,清不掉的结果回来按 JOB_MISSING 良性丢弃。
 */
@Component
public class ProgrammingPurger {
    private static final Logger log = LoggerFactory.getLogger(ProgrammingPurger.class);

    private final ProgrammingProblemMapper problems;
    private final ProgrammingProblemSampleMapper samples;
    private final ProgrammingProblemProvenanceMapper provenance;
    private final ProgrammingSubmissionMapper submissions;
    private final SubmissionCaseResultMapper caseResults;
    private final JudgeJobMapper jobs;
    private final JudgeStreamReclaimer reclaimer;
    private final ObjectStorageDeletionQueue deletionQueue;
    private final TestcaseOssProperties testcaseOss;

    ProgrammingPurger(
            ProgrammingProblemMapper problems,
            ProgrammingProblemSampleMapper samples,
            ProgrammingProblemProvenanceMapper provenance,
            ProgrammingSubmissionMapper submissions,
            SubmissionCaseResultMapper caseResults,
            JudgeJobMapper jobs,
            JudgeStreamReclaimer reclaimer,
            ObjectStorageDeletionQueue deletionQueue,
            TestcaseOssProperties testcaseOss
    ) {
        this.deletionQueue = deletionQueue;
        this.testcaseOss = testcaseOss;
        this.problems = problems;
        this.samples = samples;
        this.provenance = provenance;
        this.submissions = submissions;
        this.caseResults = caseResults;
        this.jobs = jobs;
        this.reclaimer = reclaimer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeProblems(List<Long> problemIds) {
        if (problemIds.isEmpty()) {
            return;
        }
        for (ProgrammingProblem problem : problems.selectList(new LambdaQueryWrapper<ProgrammingProblem>()
                .in(ProgrammingProblem::getId, problemIds))) {
            if (problem.hasConfirmedTestcase()) {
                deletionQueue.enqueue(testcaseOss.bucket(),
                        TestcaseObjectKey.of(problem.getId(), problem.getTestcaseSha256()));
            }
        }
        for (ProgrammingProblemProvenance packageMetadata : provenance.selectList(
                new LambdaQueryWrapper<ProgrammingProblemProvenance>()
                        .in(ProgrammingProblemProvenance::getProblemId, problemIds))) {
            deletionQueue.enqueue(testcaseOss.bucket(), packageMetadata.getSourcePackageObjectKey());
        }
        purgeSubmissionsOfProblems(problemIds);
        samples.delete(new LambdaQueryWrapper<ProgrammingProblemSample>()
                .in(ProgrammingProblemSample::getProblemId, problemIds));
        provenance.delete(new LambdaQueryWrapper<ProgrammingProblemProvenance>()
                .in(ProgrammingProblemProvenance::getProblemId, problemIds));
        problems.delete(new LambdaQueryWrapper<ProgrammingProblem>()
                .in(ProgrammingProblem::getId, problemIds));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void purgeSubmissionsOfProblems(List<Long> problemIds) {
        if (problemIds.isEmpty()) {
            return;
        }
        List<Long> submissionIds = submissions.selectList(new LambdaQueryWrapper<ProgrammingSubmission>()
                        .select(ProgrammingSubmission::getId)
                        .in(ProgrammingSubmission::getProblemId, problemIds))
                .stream()
                .map(ProgrammingSubmission::getId)
                .toList();
        if (submissionIds.isEmpty()) {
            return;
        }
        // 锁住作业行:结果落库(complete)也锁作业行,不会在测试点结果删除与作业删除之间插入新结果
        List<JudgeJob> inflight = jobs.selectList(new LambdaQueryWrapper<JudgeJob>()
                .in(JudgeJob::getSubmissionId, submissionIds)
                .ne(JudgeJob::getStatus, JudgeJobStatus.COMPLETED)
                .last("FOR UPDATE"));
        caseResults.delete(new LambdaQueryWrapper<SubmissionCaseResult>()
                .in(SubmissionCaseResult::getSubmissionId, submissionIds));
        jobs.delete(new LambdaQueryWrapper<JudgeJob>()
                .in(JudgeJob::getSubmissionId, submissionIds));
        submissions.delete(new LambdaQueryWrapper<ProgrammingSubmission>()
                .in(ProgrammingSubmission::getId, submissionIds));
        if (inflight.isEmpty()) {
            return;
        }
        List<JudgeJob> snapshot = List.copyOf(inflight);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (JudgeJob job : snapshot) {
                    try {
                        reclaimer.reclaim(job.getStreamRecordId(), job.getId() + ":" + job.getAttempt());
                    } catch (RuntimeException exception) {
                        log.warn("回收在途判题任务失败(结果会按 JOB_MISSING 丢弃)：{}", job.getId(), exception);
                    }
                }
            }
        });
    }
}
