package cn.utcy.teaching.judge.sandbox;

import cn.utcy.teaching.judge.config.JudgeProperties;
import cn.utcy.teaching.judge.model.JudgeJob;
import cn.utcy.teaching.judge.model.JudgeLanguage;
import cn.utcy.teaching.judge.model.JudgeLimits;
import cn.utcy.teaching.judge.model.JudgeStatus;
import cn.utcy.teaching.judgecontract.TestcasePackage;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "GO_JUDGE_INTEGRATION", matches = "true")
class GoJudgeIntegrationTest {

    @ParameterizedTest
    @EnumSource(JudgeLanguage.class)
    void evaluatesSupportedLanguagesAgainstRealSandbox(JudgeLanguage language) {
        URI sandboxUri = URI.create(System.getenv("GO_JUDGE_URL"));
        JudgeProperties properties = new JudgeProperties(
                "unused.jobs",
                "unused.results",
                "unused-group",
                "integration-test",
                sandboxUri,
                Path.of("target", "integration-test-cache"),
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
        ObjectMapper objectMapper = new ObjectMapper()
                .findAndRegisterModules()
                .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        GoJudgeClient client = new GoJudgeClient(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                objectMapper,
                properties
        );
        assertThat(client.version()).isEqualTo("v1.12.1");

        JudgeJob job = new JudgeJob(
                1,
                UUID.randomUUID(),
                1,
                1,
                1,
                "0".repeat(64),
                language,
                source(language),
                new JudgeLimits(2_000, 128, 64)
        );
        TestcasePackage testcasePackage = new TestcasePackage(List.of(
                new TestcasePackage.Testcase(
                        "sample",
                        "2 3\n".getBytes(StandardCharsets.UTF_8),
                        "5\n".getBytes(StandardCharsets.UTF_8),
                        new BigDecimal("100")
                )
        ));

        var result = new EvaluationEngine(client).evaluate(job, testcasePackage, 1);

        assertThat(result.status()).isEqualTo(JudgeStatus.ACCEPTED);
        assertThat(result.score()).isEqualByComparingTo("100");
        assertThat(result.cases()).singleElement()
                .extracting(caseResult -> caseResult.status())
                .isEqualTo(JudgeStatus.ACCEPTED);
    }

    private String source(JudgeLanguage language) {
        return switch (language) {
            case C17 -> """
                    #include <stdio.h>
                    int main(void) {
                        int a, b;
                        if (scanf("%d%d", &a, &b) != 2) return 1;
                        printf("%d\\n", a + b);
                        return 0;
                    }
                    """;
            case CPP20 -> """
                    #include <iostream>
                    int main() {
                        int a, b;
                        if (!(std::cin >> a >> b)) return 1;
                        std::cout << a + b << '\\n';
                        return 0;
                    }
                    """;
            case PYTHON312 -> """
                    a, b = map(int, input().split())
                    print(a + b)
                    """;
        };
    }
}
