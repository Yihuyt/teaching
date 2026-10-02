package cn.utcy.teaching.judge.worker;

import java.util.Optional;
import java.util.UUID;

public class InvalidJudgeJobException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UUID jobId;
    private final Long submissionId;
    private final Integer attempt;

    public InvalidJudgeJobException(
            String message,
            UUID jobId,
            Long submissionId,
            Integer attempt,
            Throwable cause
    ) {
        super(message, cause);
        this.jobId = jobId;
        this.submissionId = submissionId;
        this.attempt = attempt;
    }

    public Optional<Identity> identity() {
        if (jobId == null || submissionId == null || submissionId < 1 || attempt == null || attempt != 1) {
            return Optional.empty();
        }
        return Optional.of(new Identity(jobId, submissionId, attempt));
    }

    public record Identity(UUID jobId, long submissionId, int attempt) {
    }
}
