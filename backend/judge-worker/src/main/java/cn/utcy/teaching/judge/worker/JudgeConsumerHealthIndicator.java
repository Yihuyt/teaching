package cn.utcy.teaching.judge.worker;

import cn.utcy.teaching.judge.config.JudgeProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

@Component
public class JudgeConsumerHealthIndicator implements HealthIndicator {

    private enum Lifecycle {
        STARTING,
        RUNNING,
        STOPPING,
        FAILED
    }

    private enum Phase {
        STARTING,
        WAITING_FOR_CAPACITY,
        READING,
        FAILURE_BACKOFF,
        STOPPED
    }

    private final Clock clock;
    private final Duration failureWindow;
    private final int failureThreshold;
    private final Duration stallTimeout;
    private final Duration evaluationWallClock;
    private final int concurrency;
    private final Deque<Instant> readFailures = new ArrayDeque<>();
    private final Map<String, Instant> evaluationStartedAt = new HashMap<>();

    private Lifecycle lifecycle = Lifecycle.STARTING;
    private Phase phase = Phase.STARTING;
    private Instant phaseStartedAt;
    private int activeEvaluations;
    private String failureType;

    public JudgeConsumerHealthIndicator(JudgeProperties properties, Clock clock) {
        this.clock = clock;
        this.failureWindow = properties.readFailureWindow();
        this.failureThreshold = properties.readFailureThreshold();
        this.stallTimeout = properties.consumerStallTimeout();
        this.evaluationWallClock = properties.maxEvaluationWallClock();
        this.concurrency = properties.concurrency();
        this.phaseStartedAt = clock.instant();
    }

    synchronized void started() {
        lifecycle = Lifecycle.RUNNING;
        moveTo(Phase.WAITING_FOR_CAPACITY);
    }

    synchronized void waitingForCapacity() {
        moveTo(Phase.WAITING_FOR_CAPACITY);
    }

    synchronized void reading() {
        moveTo(Phase.READING);
    }

    synchronized void redisCycleSucceeded() {
        removeExpiredFailures(clock.instant());
        if (readFailures.isEmpty() && lifecycle == Lifecycle.RUNNING) {
            failureType = null;
        }
    }

    synchronized void redisCycleFailed(RuntimeException exception) {
        readFailures.addLast(clock.instant());
        failureType = exception.getClass().getSimpleName();
        moveTo(Phase.FAILURE_BACKOFF);
    }

    synchronized void evaluationStarted(String messageId) {
        activeEvaluations++;
        evaluationStartedAt.put(messageId, clock.instant());
        if (activeEvaluations > concurrency) {
            failed("活跃判题数超过配置并发度");
        }
    }

    synchronized void evaluationFinished(String messageId) {
        activeEvaluations--;
        evaluationStartedAt.remove(messageId);
        if (activeEvaluations < 0) {
            activeEvaluations = 0;
            failed("活跃判题数出现负值");
        }
    }

    synchronized void pollerStoppedUnexpectedly() {
        if (lifecycle == Lifecycle.RUNNING) {
            failed("判题任务消费线程意外退出");
        }
    }

    synchronized void stopping() {
        lifecycle = Lifecycle.STOPPING;
        moveTo(Phase.STOPPED);
    }

    @Override
    public synchronized Health health() {
        Instant now = clock.instant();
        removeExpiredFailures(now);
        if (lifecycle == Lifecycle.STARTING || lifecycle == Lifecycle.STOPPING) {
            return details(Health.outOfService(), now).build();
        }
        if (lifecycle == Lifecycle.FAILED) {
            return details(Health.down(), now).build();
        }
        if (readFailures.size() >= failureThreshold) {
            return details(Health.down(), now).build();
        }
        Duration phaseDuration = Duration.between(phaseStartedAt, now);
        if (phase == Phase.READING && phaseDuration.compareTo(stallTimeout) > 0) {
            return details(Health.down(), now).build();
        }
        if (phase == Phase.WAITING_FOR_CAPACITY
                && activeEvaluations < concurrency
                && phaseDuration.compareTo(stallTimeout) > 0) {
            return details(Health.down(), now).build();
        }
        // 满并发不是天然健康:任一评测超出墙钟上限即 DOWN(评测线程卡死时由看门狗自杀恢复)
        if (longestEvaluation(now).compareTo(evaluationWallClock) > 0) {
            return details(Health.down(), now).build();
        }
        return details(Health.up(), now).build();
    }

    private Duration longestEvaluation(Instant now) {
        Duration longest = Duration.ZERO;
        for (Instant startedAt : evaluationStartedAt.values()) {
            Duration age = Duration.between(startedAt, now);
            if (age.compareTo(longest) > 0) {
                longest = age;
            }
        }
        return longest;
    }

    private void failed(String reason) {
        lifecycle = Lifecycle.FAILED;
        failureType = reason;
    }

    private void moveTo(Phase next) {
        phase = next;
        phaseStartedAt = clock.instant();
    }

    private void removeExpiredFailures(Instant now) {
        Instant threshold = now.minus(failureWindow);
        while (!readFailures.isEmpty() && readFailures.getFirst().isBefore(threshold)) {
            readFailures.removeFirst();
        }
        if (readFailures.isEmpty() && lifecycle == Lifecycle.RUNNING) {
            failureType = null;
        }
    }

    private Health.Builder details(Health.Builder builder, Instant now) {
        return builder
                .withDetail("longestEvaluationMs", longestEvaluation(now).toMillis())
                .withDetail("lifecycle", lifecycle.name())
                .withDetail("phase", phase.name())
                .withDetail("phaseDurationMs", Duration.between(phaseStartedAt, now).toMillis())
                .withDetail("recentReadFailures", readFailures.size())
                .withDetail("activeEvaluations", activeEvaluations)
                .withDetail("failureType", failureType == null ? "none" : failureType);
    }
}
