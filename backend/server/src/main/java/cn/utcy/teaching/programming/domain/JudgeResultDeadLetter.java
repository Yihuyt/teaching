package cn.utcy.teaching.programming.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("judge_result_dead_letter")
public class JudgeResultDeadLetter {
    private static final int MAX_ERROR_MESSAGE = 2000;

    @TableId(type = IdType.AUTO)
    private Long id;
    private String streamRecordId;
    private String jobId;
    private String payload;
    private String errorType;
    private String errorMessage;
    private Instant createdAt;

    protected JudgeResultDeadLetter() {
    }

    public static JudgeResultDeadLetter of(String streamRecordId, String jobId, String payload, RuntimeException cause) {
        JudgeResultDeadLetter letter = new JudgeResultDeadLetter();
        letter.streamRecordId = streamRecordId;
        letter.jobId = jobId;
        letter.payload = payload;
        letter.errorType = cause.getClass().getSimpleName();
        String message = cause.getMessage() == null ? "" : cause.getMessage();
        letter.errorMessage = message.length() > MAX_ERROR_MESSAGE ? message.substring(0, MAX_ERROR_MESSAGE) : message;
        letter.createdAt = Instant.now();
        return letter;
    }

    public Long getId() {
        return id;
    }

    public String getStreamRecordId() {
        return streamRecordId;
    }

    public String getJobId() {
        return jobId;
    }

    public String getPayload() {
        return payload;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
