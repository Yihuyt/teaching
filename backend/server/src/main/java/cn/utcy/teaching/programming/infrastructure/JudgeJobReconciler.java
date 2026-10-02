package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.programming.domain.JudgeJobStatus;
import cn.utcy.teaching.programming.domain.ProgrammingSubmission;
import cn.utcy.teaching.programming.domain.SubmissionStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * 判题作业对账:DB 是唯一事实源,Redis 任务丢了(清空 / 丢秒级写 / 消费血统死亡)一律可从 DB 重建。
 * published 超时 → 回收旧投递(XACK 后慢 worker 的结果发布会被 XPENDING 校验原子拒绝)→
 * attempt+1 复位重投,与管理员重判同通道;重投超上限或 pending 超时 → 提交终态化,不无限重判毒丸。
 */
@Component
class JudgeJobReconciler implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JudgeJobReconciler.class);
    private static final int BATCH_LIMIT = 50;

    private final JudgeJobMapper jobs;
    private final ProgrammingSubmissionMapper submissions;
    private final JudgeStreamReclaimer reclaimer;
    private final JudgeProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final JudgeWorkerRegistry workers;

    JudgeJobReconciler(
            JudgeJobMapper jobs,
            ProgrammingSubmissionMapper submissions,
            JudgeStreamReclaimer reclaimer,
            JudgeProperties properties,
            TransactionTemplate transactions,
            Clock clock,
            JudgeWorkerRegistry workers
    ) {
        this.workers = workers;
        this.jobs = jobs;
        this.submissions = submissions;
        this.reclaimer = reclaimer;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        reconcile();
    }

    @Scheduled(fixedDelay = 60_000)
    public void reconcile() {
        try {
            republishTimedOut();
            terminalizeStuckPending();
        } catch (RuntimeException exception) {
            log.error("判题作业对账轮次失败，下一轮重试", exception);
        }
        try {
            int pruned = workers.pruneDeadConsumers();
            if (pruned > 0) {
                log.info("清理 {} 个长期离线的判题消费者", pruned);
            }
        } catch (RuntimeException exception) {
            log.warn("清理离线判题消费者失败，下一轮重试", exception);
        }
    }

    /** published 超时:结果血统已死(活 worker 20s 一心跳,阈值远大于租约),回收并重投 */
    private void republishTimedOut() {
        Instant threshold = clock.instant().minus(properties.republishTimeout());
        List<JudgeJob> timedOut = jobs.selectList(new LambdaQueryWrapper<JudgeJob>()
                .eq(JudgeJob::getStatus, JudgeJobStatus.PUBLISHED)
                .lt(JudgeJob::getPublishedAt, threshold)
                .orderByAsc(JudgeJob::getPublishedAt)
                .last("LIMIT " + BATCH_LIMIT));
        for (JudgeJob candidate : timedOut) {
            try {
                transactions.executeWithoutResult(status -> republishOne(candidate.getId(), threshold));
            } catch (RuntimeException exception) {
                log.error("判题作业重投失败，跳过并继续：{}", candidate.getId(), exception);
            }
        }
    }

    private void republishOne(String jobId, Instant threshold) {
        JudgeJob job = jobs.selectForUpdate(jobId);
        if (job == null || job.getStatus() != JudgeJobStatus.PUBLISHED
                || !job.getPublishedAt().isBefore(threshold)) {
            return;
        }
        reclaimer.reclaim(job.getStreamRecordId(), job.getId() + ":" + job.getAttempt());
        if (job.getRequeueCount() >= properties.maxRequeues()) {
            terminalize(job, "判题系统多次投递未收到结果，请联系管理员重判");
            return;
        }
        int updated = jobs.resetForRepublish(
                job.getId(), job.getAttempt() + 1, job.getRequeueCount() + 1, "published");
        if (updated != 1) {
            throw new IllegalStateException("判题作业重投复位未生效：" + job.getId());
        }
        log.warn("判题作业投递超时，已按 attempt={} 重投：jobId={}，submissionId={}",
                job.getAttempt() + 1, job.getId(), job.getSubmissionId());
    }

    /** pending 超时:发布器 500ms 一轮,长期 pending 只能是数据毒行,终态化止损 */
    private void terminalizeStuckPending() {
        Instant threshold = clock.instant().minus(properties.pendingTimeout());
        List<JudgeJob> stuck = jobs.selectList(new LambdaQueryWrapper<JudgeJob>()
                .eq(JudgeJob::getStatus, JudgeJobStatus.PENDING)
                .lt(JudgeJob::getCreatedAt, threshold)
                .orderByAsc(JudgeJob::getCreatedAt)
                .last("LIMIT " + BATCH_LIMIT));
        for (JudgeJob candidate : stuck) {
            try {
                transactions.executeWithoutResult(status -> {
                    JudgeJob job = jobs.selectForUpdate(candidate.getId());
                    if (job == null || job.getStatus() != JudgeJobStatus.PENDING
                            || !job.getCreatedAt().isBefore(threshold)) {
                        return;
                    }
                    terminalize(job, "判题任务长时间未能发布，请联系管理员重判");
                });
            } catch (RuntimeException exception) {
                log.error("判题作业终态化失败，跳过并继续：{}", candidate.getId(), exception);
            }
        }
    }

    private void terminalize(JudgeJob job, String detail) {
        ProgrammingSubmission submission = submissions.selectById(job.getSubmissionId());
        if (submission == null) {
            jobs.deleteById(job.getId());
            return;
        }
        if (!submission.getStatus().terminal()) {
            submission.complete(SubmissionStatus.SYSTEM_ERROR, null, null, null, detail);
            if (submissions.updateById(submission) != 1) {
                throw new IllegalStateException("判题提交终态化未生效：" + submission.getId());
            }
        }
        job.completed();
        if (jobs.updateById(job) != 1) {
            throw new IllegalStateException("判题作业终态化未生效：" + job.getId());
        }
        log.error("判题作业终态化：jobId={}，submissionId={}，原因={}", job.getId(), job.getSubmissionId(), detail);
    }

}
