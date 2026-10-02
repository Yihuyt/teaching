package cn.utcy.teaching.judge.worker;

import cn.utcy.teaching.judge.config.JudgeProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 消费自愈看门狗:readiness 探针只让容器显示 unhealthy,Docker 并不会重启它。
 * 消费健康持续 DOWN(线程僵死 / 连接僵死 / 读取失败连发)超过阈值时进程自行退出,
 * 交给容器 restart 策略拉起。结果写回经 Lua 原子幂等,强杀不会产生半个结果。
 */
@Component
public class JudgeConsumerWatchdog implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JudgeConsumerWatchdog.class);
    private static final Duration CHECK_INTERVAL = Duration.ofSeconds(5);

    private final JudgeConsumerHealthIndicator consumerHealth;
    private final Duration exitTimeout;
    private final Clock clock;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("judge-watchdog").factory()
    );
    private Instant downSince;

    public JudgeConsumerWatchdog(
            JudgeConsumerHealthIndicator consumerHealth,
            JudgeProperties properties,
            Clock clock
    ) {
        this.consumerHealth = consumerHealth;
        this.exitTimeout = properties.consumerDownExitTimeout();
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        scheduler.scheduleWithFixedDelay(
                this::checkAndTerminate,
                CHECK_INTERVAL.toMillis(),
                CHECK_INTERVAL.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private void checkAndTerminate() {
        if (shouldTerminate(consumerHealth.health().getStatus(), clock.instant())) {
            log.error("判题消费健康已持续 DOWN 超过 {}，进程自退出，交给容器重启策略恢复", exitTimeout);
            terminate();
        }
    }

    boolean shouldTerminate(Status status, Instant now) {
        if (!Status.DOWN.equals(status)) {
            downSince = null;
            return false;
        }
        if (downSince == null) {
            downSince = now;
            return false;
        }
        return Duration.between(downSince, now).compareTo(exitTimeout) >= 0;
    }

    /** 强杀而不是优雅退出:僵死的消费线程会卡住优雅停机本身 */
    void terminate() {
        Runtime.getRuntime().halt(1);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
