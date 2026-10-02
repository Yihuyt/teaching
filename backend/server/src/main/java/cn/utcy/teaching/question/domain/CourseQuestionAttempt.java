package cn.utcy.teaching.question.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("course_question_attempt")
public class CourseQuestionAttempt {

    @TableId
    private Long id;
    private Long questionId;
    private Long accountId;
    private Instant startedAt;
    private Instant deadlineAt;
    private Instant submittedAt;
    private Double score;
    private String answersJson;
    private String resultsJson;
    /** 超过作答时限后按空卷结算 */
    private Boolean overdue;

    protected CourseQuestionAttempt() {
    }

    public static CourseQuestionAttempt start(CourseQuestion question, long accountId, Instant now) {
        CourseQuestionAttempt attempt = new CourseQuestionAttempt();
        attempt.questionId = question.getId();
        attempt.accountId = accountId;
        attempt.startedAt = now;
        attempt.deadlineAt = question.getTimeLimitMinutes() == null
                ? null : now.plusSeconds(question.getTimeLimitMinutes() * 60L);
        return attempt;
    }

    public boolean submitted() {
        return submittedAt != null;
    }

    public void submit(Instant now, double score, String answersJson, String resultsJson, boolean overdue) {
        this.submittedAt = now;
        this.score = score;
        this.answersJson = answersJson;
        this.resultsJson = resultsJson;
        this.overdue = overdue;
    }

    public Long getId() {
        return id;
    }

    public Long getQuestionId() {
        return questionId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Double getScore() {
        return score;
    }

    public String getAnswersJson() {
        return answersJson;
    }

    public String getResultsJson() {
        return resultsJson;
    }

    public boolean getOverdue() {
        return Boolean.TRUE.equals(overdue);
    }
}
