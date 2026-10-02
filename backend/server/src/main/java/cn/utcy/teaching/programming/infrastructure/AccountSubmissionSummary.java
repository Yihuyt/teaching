package cn.utcy.teaching.programming.infrastructure;

import java.math.BigDecimal;
import java.time.Instant;

public class AccountSubmissionSummary {

    private Long accountId;
    private Long submissionCount;
    private Integer accepted;
    private BigDecimal bestScore;
    private Instant lastSubmittedAt;

    protected AccountSubmissionSummary() {
    }

    public AccountSubmissionSummary(long accountId, long submissionCount, boolean accepted, BigDecimal bestScore,
                                    Instant lastSubmittedAt) {
        this.accountId = accountId;
        this.submissionCount = submissionCount;
        this.accepted = accepted ? 1 : 0;
        this.bestScore = bestScore;
        this.lastSubmittedAt = lastSubmittedAt;
    }

    public long accountId() {
        return accountId;
    }

    public int submissionCount() {
        return submissionCount.intValue();
    }

    public boolean accepted() {
        return accepted != null && accepted == 1;
    }

    /** 从未评出分数(全部提交都是评测失败)时为 null */
    public Double bestScore() {
        return bestScore == null ? null : bestScore.doubleValue();
    }

    public Instant lastSubmittedAt() {
        return lastSubmittedAt;
    }
}
