package cn.utcy.teaching.question.infrastructure;

import java.time.Instant;

/** 教师端成绩的一行:某学生在某试题的作答汇总(MyBatis 按列别名填充) */
public class AccountAttemptSummary {

    private Long accountId;
    private Long attemptCount;
    private Double bestScore;
    private Instant lastSubmittedAt;

    protected AccountAttemptSummary() {
    }

    public AccountAttemptSummary(long accountId, long attemptCount, double bestScore, Instant lastSubmittedAt) {
        this.accountId = accountId;
        this.attemptCount = attemptCount;
        this.bestScore = bestScore;
        this.lastSubmittedAt = lastSubmittedAt;
    }

    public long accountId() {
        return accountId;
    }

    public int attemptCount() {
        return attemptCount.intValue();
    }

    public double bestScore() {
        return bestScore;
    }

    public Instant lastSubmittedAt() {
        return lastSubmittedAt;
    }
}
