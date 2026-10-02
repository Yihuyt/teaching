package cn.utcy.teaching.judge.worker;

import cn.utcy.teaching.judge.config.JudgeProperties;
import cn.utcy.teaching.judge.model.JudgeResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AtomicResultPublisher {

    private static final DefaultRedisScript<Long> PUBLISH_AND_ACK = new DefaultRedisScript<>("""
            local pending = redis.call('XPENDING', KEYS[2], ARGV[2], ARGV[3], ARGV[3], 1)
            if #pending == 0 then
              return redis.error_reply('judge job is not pending')
            end
            if redis.call('HEXISTS', KEYS[3], ARGV[4]) == 1 then
              return redis.call('XACK', KEYS[2], ARGV[2], ARGV[3])
            end
            local resultId = redis.call('XADD', KEYS[1], '*', 'payload', ARGV[1])
            redis.call('HSET', KEYS[3], ARGV[4], resultId)
            local acknowledged = redis.call('XACK', KEYS[2], ARGV[2], ARGV[3])
            if acknowledged ~= 1 then
              return redis.error_reply('judge job acknowledge failed')
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final JudgeProperties properties;

    public AtomicResultPublisher(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            JudgeProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public void publish(RecordId messageId, JudgeResult result) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化判题结果", exception);
        }
        Long acknowledged = redisTemplate.execute(
                PUBLISH_AND_ACK,
                List.of(
                        properties.resultsStream(),
                        properties.jobsStream(),
                        properties.resultsStream() + ".completed"
                ),
                payload,
                properties.consumerGroup(),
                messageId.getValue(),
                result.jobId() + ":" + result.attempt()
        );
        if (!Long.valueOf(1).equals(acknowledged)) {
            throw new IllegalStateException("Redis 未原子确认判题任务");
        }
    }
}
