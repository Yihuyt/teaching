package cn.utcy.teaching.judge.worker;

import cn.utcy.teaching.judge.model.JudgeJob;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class JudgeJobCodec {

    private final ObjectMapper objectMapper;
    private final Validator validator;

    public JudgeJobCodec(ObjectMapper objectMapper, Validator validator) {
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    public JudgeJob decode(MapRecord<String, Object, Object> record) {
        Map<Object, Object> values = record.getValue();
        if (values.size() != 1 || !values.containsKey("payload") || !(values.get("payload") instanceof String payload)) {
            throw new InvalidJudgeJobException(
                    "Redis 任务必须且只能包含 payload 字符串字段",
                    null,
                    null,
                    null,
                    null
            );
        }
        return decodePayload(payload);
    }

    JudgeJob decodePayload(String payload) {
        JsonNode tree;
        try {
            tree = objectMapper.readTree(payload);
        } catch (JsonProcessingException exception) {
            throw new InvalidJudgeJobException("任务 payload 不是合法 JSON", null, null, null, exception);
        }
        UUID jobId = identifier(tree.get("jobId"));
        Long submissionId = positiveLong(tree.get("submissionId"));
        Integer attempt = attemptNumber(tree.get("attempt"));

        try {
            JudgeJob job = objectMapper.treeToValue(tree, JudgeJob.class);
            Set<ConstraintViolation<JudgeJob>> violations = validator.validate(job);
            if (!violations.isEmpty()) {
                String fields = violations.stream()
                        .map(violation -> violation.getPropertyPath().toString())
                        .sorted()
                        .distinct()
                        .collect(Collectors.joining(", "));
                throw new InvalidJudgeJobException(
                        "任务字段校验失败：" + fields,
                        jobId,
                        submissionId,
                        attempt,
                        null
                );
            }
            return job;
        } catch (InvalidJudgeJobException exception) {
            throw exception;
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new InvalidJudgeJobException(
                    "任务 payload 不符合 schemaVersion=1 严格结构",
                    jobId,
                    submissionId,
                    attempt,
                    exception
            );
        }
    }

    private UUID identifier(JsonNode value) {
        if (value == null || !value.isTextual()) {
            return null;
        }
        try {
            return UUID.fromString(value.textValue());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Long positiveLong(JsonNode value) {
        return value != null && value.canConvertToLong() && value.longValue() > 0
                ? value.longValue()
                : null;
    }

    /** 执行次数从 1 起:重投 / 重判会带更大的 attempt(它同时是结果侧的防闪回围栏) */
    private Integer attemptNumber(JsonNode value) {
        return value != null && value.canConvertToInt() && value.intValue() >= 1 && value.intValue() <= 100
                ? value.intValue()
                : null;
    }
}
