package cn.utcy.teaching.judge.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record JudgeResult(
        int schemaVersion,
        UUID jobId,
        int attempt,
        long submissionId,
        JudgeStatus status,
        int timeUsedMs,
        int memoryUsedKb,
        BigDecimal score,
        String detail,
        List<JudgeCaseResult> cases
) {

    public static JudgeResult workerCrashLimit(JudgeJob job, int attempt) {
        return new JudgeResult(
                1,
                job.jobId(),
                attempt,
                job.submissionId(),
                JudgeStatus.WORKER_CRASH_LIMIT,
                0,
                0,
                BigDecimal.ZERO,
                "判题工作进程多次未完成该任务",
                List.of()
        );
    }

    public static JudgeResult systemError(JudgeJob job, int attempt, String detail) {
        return new JudgeResult(
                1,
                job.jobId(),
                attempt,
                job.submissionId(),
                JudgeStatus.SYSTEM_ERROR,
                0,
                0,
                BigDecimal.ZERO,
                detail,
                List.of()
        );
    }

    public static JudgeResult invalidJob(
            UUID jobId,
            long submissionId,
            int attempt,
            String detail
    ) {
        return new JudgeResult(
                1,
                jobId,
                attempt,
                submissionId,
                JudgeStatus.SYSTEM_ERROR,
                0,
                0,
                BigDecimal.ZERO,
                detail,
                List.of()
        );
    }
}
