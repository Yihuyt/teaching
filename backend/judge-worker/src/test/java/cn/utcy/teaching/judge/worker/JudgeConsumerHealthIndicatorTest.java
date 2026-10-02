package cn.utcy.teaching.judge.worker;

import cn.utcy.teaching.judge.config.JudgeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class JudgeConsumerHealthIndicatorTest {

    @org.junit.jupiter.api.Test
    void evaluationBeyondWallClockTurnsDownEvenAtFullConcurrency() {
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> now =
                new java.util.concurrent.atomic.AtomicReference<>(java.time.Instant.parse("2026-08-24T00:00:00Z"));
        java.time.Clock clock = new java.time.Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public java.time.Instant instant() { return now.get(); }
        };
        JudgeConsumerHealthIndicator indicator = new JudgeConsumerHealthIndicator(properties(), clock);
        indicator.started();
        indicator.evaluationStarted("9-0");
        indicator.waitingForCapacity();

        now.set(now.get().plusSeconds(299));
        org.assertj.core.api.Assertions.assertThat(indicator.health().getStatus())
                .isEqualTo(org.springframework.boot.actuate.health.Status.UP);

        now.set(now.get().plusSeconds(2));
        org.assertj.core.api.Assertions.assertThat(indicator.health().getStatus())
                .isEqualTo(org.springframework.boot.actuate.health.Status.DOWN);

        indicator.evaluationFinished("9-0");
        indicator.waitingForCapacity();
        org.assertj.core.api.Assertions.assertThat(indicator.health().getStatus())
                .isEqualTo(org.springframework.boot.actuate.health.Status.UP);
    }

    private static final Instant START = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void reportsStartingAndRunningStatesExplicitly() {
        MutableClock clock = new MutableClock(START);
        JudgeConsumerHealthIndicator indicator = indicator(clock);

        assertThat(indicator.health().getStatus()).isEqualTo(Status.OUT_OF_SERVICE);

        indicator.started();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsRepeatedReadFailuresWithinTheObservationWindow() {
        MutableClock clock = new MutableClock(START);
        JudgeConsumerHealthIndicator indicator = indicator(clock);
        indicator.started();

        indicator.redisCycleFailed(new IllegalStateException("first"));
        indicator.redisCycleSucceeded();
        indicator.redisCycleFailed(new IllegalStateException("second"));
        indicator.redisCycleSucceeded();
        indicator.redisCycleFailed(new IllegalStateException("third"));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

        clock.advance(Duration.ofMinutes(1).plusMillis(1));
        indicator.redisCycleSucceeded();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsAStalledRedisRead() {
        MutableClock clock = new MutableClock(START);
        JudgeConsumerHealthIndicator indicator = indicator(clock);
        indicator.started();
        indicator.reading();

        clock.advance(Duration.ofSeconds(16));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void treatsAFullEvaluationPoolAsHealthyWithinWallClock() {
        MutableClock clock = new MutableClock(START);
        JudgeConsumerHealthIndicator indicator = indicator(clock);
        indicator.started();
        indicator.evaluationStarted("1-0");
        indicator.waitingForCapacity();

        clock.advance(Duration.ofMinutes(4));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void reportsAnUnexpectedPollerExit() {
        MutableClock clock = new MutableClock(START);
        JudgeConsumerHealthIndicator indicator = indicator(clock);
        indicator.started();

        indicator.pollerStoppedUnexpectedly();

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    private JudgeConsumerHealthIndicator indicator(Clock clock) {
        return new JudgeConsumerHealthIndicator(properties(), clock);
    }

    private JudgeProperties properties() {
        return new JudgeProperties(
                "judge.jobs",
                "judge.results",
                "judge-workers",
                "worker-1",
                URI.create("http://127.0.0.1:5050"),
                Path.of("target", "testcase-cache"),
                Duration.ofMinutes(2),
                Duration.ofSeconds(20),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2),
                Duration.ofMinutes(1),
                3,
                Duration.ofSeconds(15),
                Duration.ofSeconds(90),
                1,
                5,
                Duration.ofMinutes(5),

                Duration.ofDays(7)
        );
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        private void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("测试时钟只支持 UTC");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
