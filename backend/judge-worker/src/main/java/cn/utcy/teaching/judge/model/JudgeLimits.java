package cn.utcy.teaching.judge.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record JudgeLimits(
        @Min(100) @Max(30_000) int timeLimitMs,
        @Min(16) @Max(2048) int memoryLimitMb,
        @Min(1) @Max(65_536) int outputLimitKb
) {
}
