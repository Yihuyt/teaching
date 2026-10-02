package cn.utcy.teaching.programming.infrastructure;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 判题结果消费管线健康:毒消息已被死信隔离,这里暴露的是"隔离都救不了"的停摆——
 * 同一条流记录连续瞬时失败、或长时间毫无进展。backend 不自杀,DOWN + 标记日志即告警面。
 */
@Component
public class JudgeResultPipelineHealthIndicator implements HealthIndicator {
    private static final int STALL_THRESHOLD = 5;
    private static final Duration PROGRESS_TIMEOUT = Duration.ofSeconds(60);

    private final Clock clock;
    private Instant lastProgressAt;
    private String stalledRecordId;
    private int consecutiveStalls;
    private long deadLettersSinceStart;
    private String lastFailureType;

    JudgeResultPipelineHealthIndicator(Clock clock) {
        this.clock = clock;
        this.lastProgressAt = clock.instant();
    }

    synchronized void cycleSucceeded() {
        lastProgressAt = clock.instant();
        stalledRecordId = null;
        consecutiveStalls = 0;
        lastFailureType = null;
    }

    synchronized void cycleStalled(String recordId, RuntimeException cause) {
        if (Objects.equals(stalledRecordId, recordId)) {
            consecutiveStalls += 1;
        } else {
            stalledRecordId = recordId;
            consecutiveStalls = 1;
        }
        lastFailureType = cause.getClass().getSimpleName();
    }

    synchronized void deadLettered() {
        deadLettersSinceStart += 1;
    }

    @Override
    public synchronized Health health() {
        Instant now = clock.instant();
        Duration sinceProgress = Duration.between(lastProgressAt, now);
        Health.Builder builder = consecutiveStalls >= STALL_THRESHOLD
                || (consecutiveStalls > 0 && sinceProgress.compareTo(PROGRESS_TIMEOUT) > 0)
                ? Health.down()
                : Health.up();
        return builder
                .withDetail("consecutiveStalls", consecutiveStalls)
                .withDetail("stalledRecordId", stalledRecordId == null ? "none" : stalledRecordId)
                .withDetail("sinceProgressMs", sinceProgress.toMillis())
                .withDetail("deadLettersSinceStart", deadLettersSinceStart)
                .withDetail("lastFailureType", lastFailureType == null ? "none" : lastFailureType)
                .build();
    }
}
