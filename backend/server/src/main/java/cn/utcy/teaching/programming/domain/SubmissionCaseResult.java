package cn.utcy.teaching.programming.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;

@TableName("submission_case_result")
public class SubmissionCaseResult {

    @TableId
    private Long id;
    private Long submissionId;
    private String caseId;
    private SubmissionStatus status;
    private int timeUsedMs;
    private int memoryUsedKb;
    private BigDecimal score;
    private String detail;

    protected SubmissionCaseResult() {
    }

    public SubmissionCaseResult(Long id, Long submissionId, String caseId, SubmissionStatus status,
                                int timeUsedMs, int memoryUsedKb, BigDecimal score, String detail) {
        this.id = id;
        this.submissionId = submissionId;
        this.caseId = caseId;
        this.status = status;
        this.timeUsedMs = timeUsedMs;
        this.memoryUsedKb = memoryUsedKb;
        this.score = score;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public String getCaseId() {
        return caseId;
    }

    public SubmissionStatus getStatus() {
        return status;
    }

    public int getTimeUsedMs() {
        return timeUsedMs;
    }

    public int getMemoryUsedKb() {
        return memoryUsedKb;
    }

    public BigDecimal getScore() {
        return score;
    }

    public String getDetail() {
        return detail;
    }
}
