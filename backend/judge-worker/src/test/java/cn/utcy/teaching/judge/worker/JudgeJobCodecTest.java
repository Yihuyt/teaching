package cn.utcy.teaching.judge.worker;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class JudgeJobCodecTest {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void acceptsOnlyTheVersionOneContract() {
        JudgeJobCodec codec = codec();

        var job = codec.decodePayload("""
                {
                  "schemaVersion": 1,
                  "jobId": "8a7c63fd-625b-46f4-9c9d-aee7dfbf9df4",
                  "attempt": 1,
                  "submissionId": 9,
                  "problemId": 3,
                  "testcaseSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "language": "CPP20",
                  "sourceCode": "int main(){}",
                  "limits": {
                    "timeLimitMs": 1000,
                    "memoryLimitMb": 128,
                    "outputLimitKb": 64
                  }
                }
                """);

        assertThat(job.problemId()).isEqualTo(3);
        assertThat(job.language().name()).isEqualTo("CPP20");
    }

    @Test
    void rejectsUnknownFieldsInsteadOfAdaptingThem() {
        JudgeJobCodec codec = codec();

        InvalidJudgeJobException exception = catchThrowableOfType(
                InvalidJudgeJobException.class,
                () -> codec.decodePayload("""
                {
                  "schemaVersion": 1,
                  "jobId": "8a7c63fd-625b-46f4-9c9d-aee7dfbf9df4",
                  "attempt": 1,
                  "submissionId": 9,
                  "problemId": 3,
                  "testcaseSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "testcaseObjectKey": "arbitrary.zip",
                  "language": "CPP20",
                  "sourceCode": "int main(){}",
                  "limits": {
                    "timeLimitMs": 1000,
                    "memoryLimitMb": 128,
                    "outputLimitKb": 64
                  }
                }
                """));

        assertThat(exception).hasMessageContaining("严格结构");
        assertThat(exception.identity()).get()
                .extracting(InvalidJudgeJobException.Identity::attempt)
                .isEqualTo(1);
    }

    @Test
    void acceptsRepublishedAttemptsAndRejectsOutOfRangeOnes() {
        JudgeJobCodec codec = codec();
        cn.utcy.teaching.judge.model.JudgeJob republished = codec.decodePayload(payloadWithAttempt(2));
        assertThat(republished.attempt()).isEqualTo(2);

        org.assertj.core.api.Assertions.assertThatExceptionOfType(InvalidJudgeJobException.class)
                .isThrownBy(() -> codec.decodePayload(payloadWithAttempt(0)));
        org.assertj.core.api.Assertions.assertThatExceptionOfType(InvalidJudgeJobException.class)
                .isThrownBy(() -> codec.decodePayload(payloadWithAttempt(101)));
    }

    private String payloadWithAttempt(int attempt) {
        return """
                {
                  "schemaVersion": 1,
                  "jobId": "8a7c63fd-625b-46f4-9c9d-aee7dfbf9df4",
                  "attempt": %d,
                  "submissionId": 9,
                  "problemId": 3,
                  "testcaseSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                  "language": "CPP20",
                  "sourceCode": "int main(){}",
                  "limits": {
                    "timeLimitMs": 1000,
                    "memoryLimitMb": 128,
                    "outputLimitKb": 64
                  }
                }
                """.formatted(attempt);
    }

    private JudgeJobCodec codec() {
        ObjectMapper mapper = new ObjectMapper()
                .findAndRegisterModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        return new JudgeJobCodec(mapper, validator);
    }
}
