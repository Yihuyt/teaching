package cn.utcy.teaching.judge.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@Validated
@ConfigurationProperties("teaching.judge")
public record JudgeProperties(
        @NotBlank String jobsStream,
        @NotBlank String resultsStream,
        @NotBlank String consumerGroup,
        @NotBlank String consumerName,
        @NotNull URI goJudgeUrl,
        @NotNull Path testcaseCache,
        @NotNull Duration lease,
        @NotNull Duration heartbeat,
        @NotNull Duration jobsReadBlock,
        @NotNull Duration readFailureDelay,
        @NotNull Duration readFailureWindow,
        @Min(1) @Max(100) int readFailureThreshold,
        @NotNull Duration consumerStallTimeout,
        @NotNull Duration consumerDownExitTimeout,
        @Min(1) @Max(16) int concurrency,
        @Min(2) @Max(20) int maxDeliveries,
        @NotNull Duration maxEvaluationWallClock,
        @NotNull Duration testcaseCacheRetention
) {

    public JudgeProperties {
        requirePositive(jobsReadBlock, "判题任务阻塞读取时长");
        requirePositive(readFailureDelay, "判题任务读取失败重试间隔");
        requirePositive(readFailureWindow, "判题任务读取失败观察窗口");
        requirePositive(consumerStallTimeout, "判题任务消费线程停滞阈值");
        requirePositive(consumerDownExitTimeout, "判题消费故障自退出阈值");
        if (consumerStallTimeout != null && consumerDownExitTimeout != null
                && !consumerDownExitTimeout.minus(consumerStallTimeout).isPositive()) {
            throw new IllegalArgumentException("判题消费故障自退出阈值必须大于停滞阈值");
        }
        requirePositive(maxEvaluationWallClock, "单个判题任务墙钟上限");
        requirePositive(testcaseCacheRetention, "测试包缓存保留期");
        if (maxEvaluationWallClock != null && testcaseCacheRetention != null
                && !testcaseCacheRetention.minus(maxEvaluationWallClock).isPositive()) {
            throw new IllegalArgumentException("测试包缓存保留期必须大于单任务墙钟上限");
        }
        if (lease != null && maxEvaluationWallClock != null
                && !maxEvaluationWallClock.minus(lease).isPositive()) {
            throw new IllegalArgumentException("单个判题任务墙钟上限必须大于租约时长");
        }
        if (lease != null && heartbeat != null && !heartbeat.minus(lease).isNegative()) {
            throw new IllegalArgumentException("判题任务心跳间隔必须小于租约时长");
        }
    }

    private static void requirePositive(Duration value, String field) {
        if (value != null && (value.isZero() || value.isNegative())) {
            throw new IllegalArgumentException(field + "必须大于 0");
        }
    }
}
