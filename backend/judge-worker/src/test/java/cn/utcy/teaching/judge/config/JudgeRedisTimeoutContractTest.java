package cn.utcy.teaching.judge.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JudgeRedisTimeoutContractTest {

    @Test
    void acceptsCommandTimeoutWithExplicitSafetyMargin() {
        assertThatNoException().isThrownBy(() ->
                JudgeRedisTimeoutContract.verify(
                        Duration.ofSeconds(10),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(15)
                )
        );
    }

    @Test
    void rejectsMissingCommandTimeout() {
        assertThatThrownBy(() -> JudgeRedisTimeoutContract.verify(
                null,
                Duration.ofSeconds(2),
                Duration.ofSeconds(15)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.data.redis.timeout");
    }

    @Test
    void rejectsCommandTimeoutWithoutRequiredMargin() {
        assertThatThrownBy(() ->
                JudgeRedisTimeoutContract.verify(
                        Duration.ofSeconds(6),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(15)
                )
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.data.redis.timeout")
                .hasMessageContaining("teaching.judge.jobs-read-block");
    }

    @Test
    void rejectsStallTimeoutThatCannotOutliveRedisCommand() {
        assertThatThrownBy(() -> JudgeRedisTimeoutContract.verify(
                Duration.ofSeconds(10),
                Duration.ofSeconds(2),
                Duration.ofSeconds(10)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("teaching.judge.consumer-stall-timeout")
                .hasMessageContaining("spring.data.redis.timeout");
    }
}
