package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.shared.actor.CurrentActor;
import cn.utcy.teaching.shared.actor.OwnershipPolicy;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.StreamInfo;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 判题机在线情况:直接读判题任务流消费者组的成员(XINFO CONSUMERS),不另设注册表——
 * 每个判题副本以唯一消费者名加入组,空闲时也每 2 秒 XREADGROUP 一次,idle 超过阈值即视为离线。
 * 组尚未创建(从未有判题机启动)时返回空列表。
 */
@Component
public class JudgeWorkerRegistry {
    /** 判题机空闲轮询 2 秒一次、心跳 20 秒一次,超过 1 分钟没有任何交互就是离线或僵死 */
    static final Duration ALIVE_THRESHOLD = Duration.ofMinutes(1);
    static final Duration PRUNE_THRESHOLD = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final JudgeProperties properties;
    private final CurrentActor currentActor;
    private final OwnershipPolicy ownership;

    JudgeWorkerRegistry(StringRedisTemplate redis, JudgeProperties properties,
                        CurrentActor currentActor, OwnershipPolicy ownership) {
        this.redis = redis;
        this.properties = properties;
        this.currentActor = currentActor;
        this.ownership = ownership;
    }

    public List<JudgeWorkerView> workers() {
        ownership.requirePlatformAdministrator(currentActor.require());
        StreamInfo.XInfoConsumers consumers;
        try {
            consumers = redis.opsForStream().consumers(properties.jobsStream(), properties.consumerGroup());
        } catch (DataAccessException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("NOGROUP")) {
                return List.of();
            }
            throw exception;
        }
        return consumers.stream()
                .map(consumer -> new JudgeWorkerView(
                        consumer.consumerName(),
                        consumer.pendingCount(),
                        consumer.idleTimeMs(),
                        consumer.idleTimeMs() < ALIVE_THRESHOLD.toMillis()))
                .toList();
    }

    /** 离线超过 1 小时且没有在途任务的消费者是重启留下的尸体(非优雅停机不会自行注销),删掉;返回删掉的个数 */
    public int pruneDeadConsumers() {
        StreamInfo.XInfoConsumers consumers;
        try {
            consumers = redis.opsForStream().consumers(properties.jobsStream(), properties.consumerGroup());
        } catch (DataAccessException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains("NOGROUP")) {
                return 0;
            }
            throw exception;
        }
        int pruned = 0;
        for (StreamInfo.XInfoConsumer consumer : consumers) {
            if (consumer.pendingCount() == 0 && consumer.idleTimeMs() >= PRUNE_THRESHOLD.toMillis()) {
                redis.opsForStream().deleteConsumer(properties.jobsStream(),
                        Consumer.from(properties.consumerGroup(), consumer.consumerName()));
                pruned++;
            }
        }
        return pruned;
    }

    public record JudgeWorkerView(String name, long pending, long idleMs, boolean alive) {
    }
}
