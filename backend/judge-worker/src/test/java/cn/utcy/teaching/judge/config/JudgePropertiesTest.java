package cn.utcy.teaching.judge.config;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JudgePropertiesTest {

    @Test
    void acceptsPositiveQueueDurations() {
        assertThatNoException().isThrownBy(() -> properties(
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        ));
    }

    @Test
    void rejectsNonPositiveJobsReadBlock() {
        assertThatThrownBy(() -> properties(Duration.ZERO, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("判题任务阻塞读取时长必须大于 0");
    }

    @Test
    void rejectsNonPositiveReadFailureDelay() {
        assertThatThrownBy(() -> properties(Duration.ofSeconds(2), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("判题任务读取失败重试间隔必须大于 0");
    }

    private JudgeProperties properties(Duration jobsReadBlock, Duration readFailureDelay) {
        return new JudgeProperties(
                "judge.jobs",
                "judge.results",
                "judge-workers",
                "worker-1",
                URI.create("http://127.0.0.1:5050"),
                Path.of("target", "testcase-cache"),
                Duration.ofMinutes(2),
                Duration.ofSeconds(20),
                jobsReadBlock,
                readFailureDelay,
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
}
