package cn.utcy.teaching.programming.infrastructure;

import cn.utcy.teaching.programming.domain.JudgeJob;
import cn.utcy.teaching.programming.domain.JudgeJobStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** 判题投递健康:最旧待发布作业滞留超过阈值即 DOWN(发布器 500ms 一轮,滞留只能是投递侧故障) */
@Component
public class JudgeJobPublisherHealthIndicator implements HealthIndicator {

    private static final Duration PENDING_TIMEOUT = Duration.ofMinutes(2);

    private final JudgeJobMapper jobs;
    private final Clock clock;

    JudgeJobPublisherHealthIndicator(JudgeJobMapper jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    @Override
    public Health health() {
        List<JudgeJob> oldest = jobs.selectList(new LambdaQueryWrapper<JudgeJob>()
                .eq(JudgeJob::getStatus, JudgeJobStatus.PENDING)
                .orderByAsc(JudgeJob::getCreatedAt)
                .last("LIMIT 1"));
        if (oldest.isEmpty()) {
            return Health.up().withDetail("oldestPendingAgeMs", 0).build();
        }
        Duration age = Duration.between(oldest.getFirst().getCreatedAt(), clock.instant());
        Health.Builder builder = age.compareTo(PENDING_TIMEOUT) > 0 ? Health.down() : Health.up();
        return builder.withDetail("oldestPendingAgeMs", age.toMillis()).build();
    }
}
