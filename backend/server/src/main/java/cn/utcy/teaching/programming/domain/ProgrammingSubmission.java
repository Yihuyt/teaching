package cn.utcy.teaching.programming.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.Instant;

@TableName("programming_submission")
public class ProgrammingSubmission {

    @TableId
    private Long id;
    private Long problemId;
    private Long accountId;
    private ProgrammingLanguage language;
    private String sourceCode;
    private SubmissionStatus status;
    private Integer timeUsedMs;
    private Integer memoryUsedKb;
    private BigDecimal score;
    private String resultDetail;
    private Instant submittedAt;
    private Instant completedAt;
    private Instant updatedAt;

    protected ProgrammingSubmission() {
    }

    public ProgrammingSubmission(Long id, Long problemId, Long accountId, ProgrammingLanguage language,
                                 String sourceCode, SubmissionStatus status, Integer timeUsedMs,
                                 Integer memoryUsedKb, BigDecimal score, String resultDetail,
                                 Instant submittedAt, Instant completedAt, Instant updatedAt) {
        this.id = id;
        this.problemId = problemId;
        this.accountId = accountId;
        this.language = language;
        this.sourceCode = sourceCode;
        this.status = status;
        this.timeUsedMs = timeUsedMs;
        this.memoryUsedKb = memoryUsedKb;
        this.score = score;
        this.resultDetail = resultDetail;
        this.submittedAt = submittedAt;
        this.completedAt = completedAt;
        this.updatedAt = updatedAt;
    }

    public static ProgrammingSubmission create(
            long problemId,
            long accountId,
            ProgrammingLanguage language,
            String sourceCode
    ) {
        Instant now = Instant.now();
        return new ProgrammingSubmission(null, problemId, accountId, language, sourceCode,
                SubmissionStatus.QUEUED, null, null, null, null, now, null, now);
    }

    public void complete(SubmissionStatus status, Integer timeUsedMs, Integer memoryUsedKb,
                         BigDecimal score, String resultDetail) {
        if (!status.terminal()) {
            throw new IllegalArgumentException("判题结果必须是终态");
        }
        this.status = status;
        this.timeUsedMs = timeUsedMs;
        this.memoryUsedKb = memoryUsedKb;
        this.score = score;
        this.resultDetail = resultDetail;
        this.completedAt = Instant.now();
        this.updatedAt = this.completedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getProblemId() {
        return problemId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public ProgrammingLanguage getLanguage() {
        return language;
    }

    public String getSourceCode() {
        return sourceCode;
    }

    public SubmissionStatus getStatus() {
        return status;
    }

    public Integer getTimeUsedMs() {
        return timeUsedMs;
    }

    public Integer getMemoryUsedKb() {
        return memoryUsedKb;
    }

    public BigDecimal getScore() {
        return score;
    }

    public String getResultDetail() {
        return resultDetail;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
