package cn.utcy.teaching.judge.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record JudgeJob(
        @Min(1) @Max(1) int schemaVersion,
        @NotNull UUID jobId,
        @Min(1) @Max(100) int attempt,
        @Min(1) long submissionId,
        @Min(1) long problemId,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String testcaseSha256,
        @NotNull JudgeLanguage language,
        @NotBlank @Size(max = 131_072) String sourceCode,
        @NotNull @Valid JudgeLimits limits
) {
}
