package cn.utcy.teaching.judge.sandbox;

import cn.utcy.teaching.judgecontract.TestcasePackage;
import cn.utcy.teaching.judge.model.JudgeJob;
import cn.utcy.teaching.judge.model.JudgeLanguage;
import cn.utcy.teaching.judge.model.JudgeLimits;
import cn.utcy.teaching.judge.model.JudgeResult;
import cn.utcy.teaching.judge.model.JudgeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvaluationEngineTest {

    @Test
    void normalizesLineEndingsAndTrailingWhitespaceOnly() {
        assertThat(EvaluationEngine.normalizeOutput("1 2  \r\n3\r\n\r\n"))
                .isEqualTo("1 2\n3");
        assertThat(EvaluationEngine.normalizeOutput("1  2\n"))
                .isNotEqualTo(EvaluationEngine.normalizeOutput("1 2\n"));
    }

    @Test
    @DisplayName("程序非零退出(go-judge 状态 Nonzero Exit Status)是运行时错误,不是判题环境故障")
    void nonzeroExitIsRuntimeErrorNotInfraFailure() {
        GoJudgeClient client = mock(GoJudgeClient.class);
        when(client.run(any(), any())).thenReturn(
                new GoJudgeResponse("Accepted", 0, null, 0, 0, 0, 0, Map.of(), Map.of(), List.of()),
                new GoJudgeResponse("Nonzero Exit Status", 1, null, 5_000_000, 1_048_576, 0, 0,
                        Map.of("stderr", "ZeroDivisionError: division by zero"), Map.of(), List.of()));
        EvaluationEngine engine = new EvaluationEngine(client);
        JudgeJob job = new JudgeJob(1, UUID.randomUUID(), 1, 21L, 6L, "0".repeat(64),
                JudgeLanguage.PYTHON312, "print(1/0)", new JudgeLimits(1000, 256, 1024));
        TestcasePackage testcases = new TestcasePackage(List.of(new TestcasePackage.Testcase(
                "1", "1 2".getBytes(StandardCharsets.UTF_8), "3".getBytes(StandardCharsets.UTF_8),
                BigDecimal.TEN)));

        JudgeResult result = engine.evaluate(job, testcases, 1);

        assertThat(result.status()).isEqualTo(JudgeStatus.RUNTIME_ERROR);
        assertThat(result.cases()).singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo(JudgeStatus.RUNTIME_ERROR));
    }
}
