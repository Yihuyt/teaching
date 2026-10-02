package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.application.JudgeResult;
import cn.utcy.teaching.programming.application.JudgeResultApplicationService;
import cn.utcy.teaching.programming.application.JudgeResultApplicationService.Completion;
import cn.utcy.teaching.programming.application.JudgeResultApplicationService.CompletionOutcome;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 判题结果消费:逐条处理、逐条推进 checkpoint。
 * 无法应用的结果单条隔离进死信表,主流继续流动——毒消息不允许堵死全站判题;
 * 只有瞬时故障(数据库 / Redis)才停在原地等下一轮重试。
 */
@Component
class JudgeResultListener {

    private static final Logger log = LoggerFactory.getLogger(JudgeResultListener.class);
    private static final String INITIAL_STREAM_ID = "0-0";
    private static final Set<String> RESULT_FIELDS = Set.of(
            "schemaVersion",
            "jobId",
            "attempt",
            "submissionId",
            "status",
            "timeUsedMs",
            "memoryUsedKb",
            "score",
            "detail",
            "cases");
    private static final Set<String> CASE_FIELDS = Set.of(
            "caseId",
            "status",
            "timeUsedMs",
            "memoryUsedKb",
            "score",
            "detail");
    /**
     * 推进 checkpoint 并清理本条结果与其作业痕迹。对 Redis 状态残缺(发布索引缺失、
     * 记录 ID 不一致——重投 / 索引丢失后属正常现象)容错:DB 才是事实源,能清多少清多少。
     */
    private static final DefaultRedisScript<String> ADVANCE_SCRIPT =
            new DefaultRedisScript<>("""
                    local jobRecordId = redis.call('HGET', KEYS[5], ARGV[2])
                    if jobRecordId and ARGV[3] ~= '' and ARGV[3] ~= jobRecordId then
                        jobRecordId = false
                    end
                    redis.call('SET', KEYS[1], ARGV[1])
                    redis.call('XDEL', KEYS[2], ARGV[1])
                    redis.call('HDEL', KEYS[3], ARGV[2])
                    if jobRecordId then
                        redis.call('XDEL', KEYS[4], jobRecordId)
                        redis.call('HDEL', KEYS[5], ARGV[2])
                    end
                    return 'OK'
                    """, String.class);

    private final StringRedisTemplate redis;
    private final JudgeProperties properties;
    private final JudgeResultApplicationService resultService;
    private final JudgeResultDeadLetterStore deadLetters;
    private final JudgeResultPipelineHealthIndicator health;
    private final ObjectMapper objectMapper;

    JudgeResultListener(
            StringRedisTemplate redis,
            JudgeProperties properties,
            JudgeResultApplicationService resultService,
            JudgeResultDeadLetterStore deadLetters,
            JudgeResultPipelineHealthIndicator health,
            ObjectMapper objectMapper
    ) {
        this.redis = redis;
        this.properties = properties;
        this.resultService = resultService;
        this.deadLetters = deadLetters;
        this.health = health;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 500)
    public void receiveResults() {
        String checkpointKey = "judge.results.checkpoint:" + properties.resultConsumerName();
        List<MapRecord<String, Object, Object>> records;
        try {
            String checkpoint = redis.opsForValue().get(checkpointKey);
            records = redis.opsForStream().read(
                    StreamReadOptions.empty().count(50),
                    StreamOffset.create(
                            properties.resultsStream(),
                            ReadOffset.from(checkpoint == null ? INITIAL_STREAM_ID : checkpoint)));
        } catch (DataAccessException exception) {
            health.cycleStalled("(read)", exception);
            log.error("JUDGE_PIPELINE_STALLED 读取判题结果流失败，下一轮重试", exception);
            return;
        }
        if (records == null) {
            throw new IllegalStateException("Redis Stream 读取结果不符合契约");
        }
        for (MapRecord<String, Object, Object> record : records) {
            String recordId = record.getId().getValue();
            try {
                processOne(checkpointKey, record);
            } catch (DataAccessException exception) {
                // 瞬时故障:停在本条,checkpoint 不动,下一轮从这里重试
                health.cycleStalled(recordId, exception);
                log.error("JUDGE_PIPELINE_STALLED 判题结果处理遇到瞬时故障，停在 {} 等待重试", recordId, exception);
                return;
            }
        }
        health.cycleSucceeded();
    }

    private void processOne(String checkpointKey, MapRecord<String, Object, Object> record) {
        String recordId = record.getId().getValue();
        String payload = extractPayload(record);
        if (payload == null) {
            isolate(checkpointKey, record, null, null,
                    new IllegalStateException("判题结果 Stream 记录必须且只能包含字符串 payload"));
            return;
        }
        JudgeResult result;
        try {
            result = parse(payload);
        } catch (RuntimeException exception) {
            isolate(checkpointKey, record, null, payload, exception);
            return;
        }
        Completion completion;
        try {
            completion = resultService.complete(result);
        } catch (DataAccessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 业务性失败(冲突终态 / 校验不过 / 数据残缺):隔离本条,主流继续
            isolate(checkpointKey, record, result.jobId(), payload, exception);
            return;
        }
        if (completion.outcome() == CompletionOutcome.STALE_ATTEMPT
                || completion.outcome() == CompletionOutcome.JOB_MISSING) {
            log.info("判题结果按 {} 丢弃：recordId={}，jobId={}", completion.outcome(), recordId, result.jobId());
        }
        advance(checkpointKey, recordId,
                result.jobId() + ":" + result.attempt(),
                completion.jobStreamRecordId());
    }

    /** 写死信(独立事务,成功才推进);死信写入失败按瞬时故障冒泡,不推进 */
    private void isolate(
            String checkpointKey,
            MapRecord<String, Object, Object> record,
            String jobId,
            String payload,
            RuntimeException cause
    ) {
        String recordId = record.getId().getValue();
        deadLetters.record(recordId, jobId, payload == null ? record.getValue().toString() : payload, cause);
        health.deadLettered();
        log.error("判题结果已隔离进死信：recordId={}，jobId={}，原因={}", recordId, jobId, cause.getMessage());
        advance(checkpointKey, recordId, jobId == null ? "" : jobId, null);
    }

    private void advance(String checkpointKey, String recordId, String publicationKey, String jobStreamRecordId) {
        String confirmed = redis.execute(
                ADVANCE_SCRIPT,
                List.of(
                        checkpointKey,
                        properties.resultsStream(),
                        properties.resultsStream() + ".completed",
                        properties.jobsStream(),
                        properties.jobsStream() + ".published"),
                recordId,
                publicationKey,
                jobStreamRecordId == null ? "" : jobStreamRecordId);
        if (!"OK".equals(confirmed)) {
            throw new IllegalStateException("Redis 未确认判题流推进");
        }
    }

    private String extractPayload(MapRecord<String, Object, Object> record) {
        Map<Object, Object> values = record.getValue();
        if (values.size() != 1 || !(values.get("payload") instanceof String payload)) {
            return null;
        }
        return payload;
    }

    JudgeResult parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalStateException("判题结果 payload 不能为空");
        }
        try {
            JsonNode tree = objectMapper.readTree(payload);
            requireExactObject(tree, RESULT_FIELDS, "判题结果");
            JsonNode cases = tree.get("cases");
            if (cases == null || !cases.isArray()) {
                throw new IllegalStateException("判题结果 cases 必须是数组");
            }
            for (JsonNode caseResult : cases) {
                requireExactObject(caseResult, CASE_FIELDS, "测试点结果");
            }
            return objectMapper.treeToValue(tree, JudgeResult.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("判题结果 payload 不符合 JSON 契约", exception);
        }
    }

    private void requireExactObject(JsonNode node, Set<String> fields, String name) {
        if (node == null || !node.isObject()) {
            throw new IllegalStateException(name + "必须是 JSON 对象");
        }
        Set<String> actual = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(fields)) {
            throw new IllegalStateException(name + "字段不符合 schemaVersion=1 严格结构");
        }
    }
}
