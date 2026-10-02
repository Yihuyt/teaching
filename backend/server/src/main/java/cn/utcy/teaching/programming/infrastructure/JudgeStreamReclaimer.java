package cn.utcy.teaching.programming.infrastructure;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回收一次判题投递在 Redis 里的痕迹:XACK(踢出消费组 PEL,让慢 worker 的结果发布被
 * XPENDING 校验原子拒绝)→ XDEL 任务记录 → HDEL 发布索引。全部步骤对 key 缺失幂等。
 */
@Component
public class JudgeStreamReclaimer {

    private static final DefaultRedisScript<String> RECLAIM_SCRIPT =
            new DefaultRedisScript<>("""
                    if ARGV[1] ~= '' then
                        redis.call('XACK', KEYS[1], ARGV[2], ARGV[1])
                        redis.call('XDEL', KEYS[1], ARGV[1])
                    end
                    redis.call('HDEL', KEYS[2], ARGV[3])
                    return 'OK'
                    """, String.class);

    private final StringRedisTemplate redis;
    private final JudgeProperties properties;

    JudgeStreamReclaimer(StringRedisTemplate redis, JudgeProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    public void reclaim(String streamRecordId, String publicationKey) {
        String confirmed = redis.execute(
                RECLAIM_SCRIPT,
                List.of(properties.jobsStream(), properties.jobsStream() + ".published"),
                streamRecordId == null ? "" : streamRecordId,
                properties.consumerGroup(),
                publicationKey);
        if (!"OK".equals(confirmed)) {
            throw new IllegalStateException("Redis 未确认判题任务回收");
        }
    }
}
