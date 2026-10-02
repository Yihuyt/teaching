package cn.utcy.teaching.programming.application;

import cn.utcy.teaching.programming.domain.SubmissionStatus;

import java.math.BigDecimal;
import java.util.List;

public record JudgeResult(
        int schemaVersion,
        String jobId,
        int attempt,
        long submissionId,
        SubmissionStatus status,
        int timeUsedMs,
        int memoryUsedKb,
        BigDecimal score,
        String detail,
        List<CaseResult> cases
) {
    public record CaseResult(
            String caseId,
            SubmissionStatus status,
            int timeUsedMs,
            int memoryUsedKb,
            BigDecimal score,
            String detail
    ) {
    }
}
