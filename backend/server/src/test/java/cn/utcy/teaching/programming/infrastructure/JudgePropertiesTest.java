package cn.utcy.teaching.programming.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class JudgePropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration.class)
            .withPropertyValues(
                    "teaching.judge.jobs-stream=judge.jobs",
                    "teaching.judge.results-stream=judge.results",
                    "teaching.judge.consumer-group=judge-workers",
                    "teaching.judge.republish-timeout=10m",
                    "teaching.judge.pending-timeout=15m",
                    "teaching.judge.max-requeues=3");

    @Test
    void bindsExplicitConsumerInstanceName() {
        contextRunner
                .withPropertyValues("teaching.judge.result-consumer-name=backend-1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JudgeProperties.class).resultConsumerName())
                            .isEqualTo("backend-1");
                });
    }

    @Test
    void refusesToStartWithoutConsumerInstanceName() {
        contextRunner.run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JudgeProperties.class)
    static class TestConfiguration {
    }
}
