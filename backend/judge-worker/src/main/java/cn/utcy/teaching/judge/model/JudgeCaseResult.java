package cn.utcy.teaching.judge.model;

import java.math.BigDecimal;

public record JudgeCaseResult(
        String caseId,
        JudgeStatus status,
        int timeUsedMs,
        int memoryUsedKb,
        BigDecimal score,
        String detail
) {
}
