package cn.utcy.teaching.programming.infrastructure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties("teaching.judge")
public record JudgeProperties(
        @NotBlank String jobsStream,
        @NotBlank String resultsStream,
        @NotBlank String resultConsumerName,
        @NotBlank String consumerGroup,
        @NotNull Duration republishTimeout,
        @NotNull Duration pendingTimeout,
        int maxRequeues
) {

    public JudgeProperties {
        if (republishTimeout != null && (republishTimeout.isZero() || republishTimeout.isNegative())) {
            throw new IllegalArgumentException("判题重投超时阈值必须大于 0");
        }
        if (pendingTimeout != null && (pendingTimeout.isZero() || pendingTimeout.isNegative())) {
            throw new IllegalArgumentException("判题待发布超时阈值必须大于 0");
        }
        if (maxRequeues < 1 || maxRequeues > 10) {
            throw new IllegalArgumentException("判题重投次数上限必须在 1 到 10 之间");
        }
    }
}
