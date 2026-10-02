package cn.utcy.teaching.judge.worker;

import cn.utcy.teaching.judge.config.JudgeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class JudgeConsumerWatchdogTest {

    private static final Instant BASE = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void terminatesOnlyAfterContinuousDownBeyondThreshold() {
        JudgeConsumerWatchdog watchdog = watchdog();

        assertThat(watchdog.shouldTerminate(Status.DOWN, BASE)).isFalse();
        assertThat(watchdog.shouldTerminate(Status.DOWN, BASE.plusSeconds(89))).isFalse();
        assertThat(watchdog.shouldTerminate(Status.DOWN, BASE.plusSeconds(90))).isTrue();
    }

    @Test
    void recoveryResetsTheDownTimer() {
        JudgeConsumerWatchdog watchdog = watchdog();

        assertThat(watchdog.shouldTerminate(Status.DOWN, BASE)).isFalse();
        assertThat(watchdog.shouldTerminate(Status.UP, BASE.plusSeconds(60))).isFalse();
        assertThat(watchdog.shouldTerminate(Status.DOWN, BASE.plusSeconds(120))).isFalse();
        assertThat(watchdog.shouldTerminate(Status.DOWN, BASE.plusSeconds(209))).isFalse();
        assertThat(watchdog.shouldTerminate(Status.DOWN, BASE.plusSeconds(210))).isTrue();
    }

    @Test
    void startingAndStoppingStatesNeverTerminate() {
        JudgeConsumerWatchdog watchdog = watchdog();

        assertThat(watchdog.shouldTerminate(Status.OUT_OF_SERVICE, BASE)).isFalse();
        assertThat(watchdog.shouldTerminate(Status.OUT_OF_SERVICE, BASE.plusSeconds(600))).isFalse();
    }

    private static JudgeConsumerWatchdog watchdog() {
        JudgeProperties properties = new JudgeProperties(
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
        return new JudgeConsumerWatchdog(
                mock(JudgeConsumerHealthIndicator.class),
                properties,
                Clock.systemUTC()
        );
    }
}
